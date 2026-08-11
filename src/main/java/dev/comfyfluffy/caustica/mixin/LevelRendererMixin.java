package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
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
	@Shadow
	@Final
	private OptionsRenderState optionsRenderState;

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
		Runnable playerCompiledSectionCallback = this.levelRenderState.playerCompiledSectionCallback;
		boolean waitingForRtPlayerSection = false;
		if (VanillaRenderController.rtRuntimeWorkRequested() && playerCompiledSectionCallback != null) {
			if (RtTerrain.isSectionReady(cameraState.blockPos)) {
				playerCompiledSectionCallback.run();
				VanillaRenderController.INSTANCE.markRtPlayerSectionReady();
			} else {
				waitingForRtPlayerSection = true;
			}
		}

		if (!VanillaRenderController.INSTANCE.shouldCancelLevelRenderer(waitingForRtPlayerSection)) {
			return;
		}

		caustica$maintainVanillaSections(cameraState);
		caustica$drainVanillaGizmos();
		VanillaRenderController.INSTANCE.markWorldSkipped();
		ci.cancel();
	}

	/**
	 * Keep vanilla's chunk-visibility bookkeeping running while RT owns the world.
	 *
	 * <p>{@code LevelExtractor.extract} feeds {@link net.minecraft.client.renderer.SectionOcclusionGraph}
	 * from {@code ClientChunkCache}'s loaded-chunk and empty-section <em>deltas</em>, and clears those sets
	 * (via {@code flipUpdateTrackingSets}) whether or not anyone consumed them. {@code render} is the only
	 * consumer, so cancelling it without this call silently drops every chunk load that happens while RT is
	 * on. The graph's {@code loadedChunks} set then no longer matches the world and every section parks in
	 * {@code sectionsWaitingForChunkLoads} — vanilla renders sky and particles but no terrain or entities,
	 * with no recovery short of a dimension change. Repositioning is part of the same contract: the view
	 * area must follow the camera or the graph rebuilds around a stale section grid.
	 *
	 * <p>Section meshing ({@code compileSections} and the GPU upload) is deliberately <em>not</em> run: RT
	 * renders the world, so vanilla meshes would be pure cost. {@link LevelExtractorMixin} holds the
	 * matching half of that decision by keeping sections marked dirty, so they compile when RT stops.
	 */
	private void caustica$maintainVanillaSections(CameraRenderState cameraState) {
		LevelRenderer renderer = (LevelRenderer) (Object) this;
		((LevelRendererAccessor) renderer).caustica$repositionCamera(cameraState);
		renderer.sectionOcclusionGraph().update(
				cameraState, this.optionsRenderState.fov, this.levelRenderState.chunkLoadingRenderState);
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
