package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.VanillaTerrainSuspension;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true)
	private void caustica$skipUnrenderedEntityOutline(CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("world.outlineGate")) {
			// Cancelling world rendering also skips the outline target's clear and population.
			if (CausticaClientComposition.current().renderController().wasWorldSkippedThisFrame()) ci.cancel();
		}
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void caustica$cancelVanillaWorld(
			GraphicsResourceAllocator resourceAllocator,
			DeltaTracker deltaTracker,
			boolean renderOutline,
			CameraRenderState cameraState,
			Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog,
			Vector4f fogColor,
			boolean shouldRenderSky,
			CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("world.maintenance")) {
			try (var ignored = CausticaClientComposition.current().runtime().profileStage("host.worldMaintenance")) {
				Runnable playerCompiledSectionCallback = this.levelRenderState.playerCompiledSectionCallback;
				boolean waitingForRtPlayerSection = false;
				if (CausticaClientComposition.current().renderController().rtRuntimeWorkRequested() && playerCompiledSectionCallback != null) {
					if (CausticaClientComposition.current().terrain().isSectionReady(cameraState.blockPos)) {
						playerCompiledSectionCallback.run();
						CausticaClientComposition.current().renderController().markRtPlayerSectionReady();
					} else {
						waitingForRtPlayerSection = true;
					}
				}

				if (!CausticaClientComposition.current().renderController().shouldCancelLevelRenderer(waitingForRtPlayerSection)) {
					return;
				}

				LevelRenderer renderer = (LevelRenderer) (Object) this;
				CausticaClientComposition.current().renderController().suspendVanillaTerrain(() -> {
					renderer.viewArea().releaseAllBuffers();
					renderer.sectionRenderDispatcher().clearCompileQueue();
					renderer.sectionOcclusionGraph().waitAndReset(renderer.viewArea());
					renderer.clearVisibleSections();
				});
				// Extraction rotates its delta buffers every frame; copy membership before they are reused.
				var residency = (SectionOcclusionGraphAccessor) renderer.sectionOcclusionGraph();
				var changes = this.levelRenderState.chunkLoadingRenderState;
				VanillaTerrainSuspension.applyDelta(residency.caustica$getLoadedChunks(),
						changes.addedLoadedChunks, changes.removedLoadedChunks);
				VanillaTerrainSuspension.applyDelta(residency.caustica$getEmptySections(),
						changes.addedEmptySections, changes.removedEmptySections);
				caustica$drainVanillaGizmos();
				CausticaClientComposition.current().renderController().markWorldSkipped();
				ci.cancel();
			}
		}
	}

	/**
	 * Consume the frame's gizmos and drop them, because RT draws none.
	 *
	 * <p>{@code LevelExtractor.extractGizmos} pushes into {@code renderThreadGizmos} every frame and
	 * {@code submitFeatures}' {@code finalizeGizmoCollection} is its only consumer, so cancelling
	 * {@code render} lets the collector grow without bound — and expired gizmos are pruned by the same
	 * drain, so nothing ages out either. The first vanilla-rendered frame after a long RT session then
	 * flushes the whole backlog into one buffer and trips {@code BufferBuilder}'s 16M vertex limit.</p>
	 */
	private void caustica$drainVanillaGizmos() {
		((LevelRendererAccessor) (Object) this).caustica$getRenderThreadGizmos().drainGizmos();
	}
}
