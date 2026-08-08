package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
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
		VanillaRenderController.INSTANCE.markWorldSkipped();
		ci.cancel();
	}

	/** Run the non-raster tail that a HEAD cancellation would otherwise skip. */
	private void caustica$maintainVanillaSections(CameraRenderState cameraState) {
		LevelRenderer renderer = (LevelRenderer) (Object) this;
		LevelRendererAccessor accessor = (LevelRendererAccessor) renderer;
		accessor.caustica$repositionCamera(cameraState);
		accessor.caustica$compileSections(cameraState);

		SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
		if (dispatcher != null) {
			dispatcher.lock();
			try {
				dispatcher.uploadTerrainBuffersToGpu();
			} finally {
				dispatcher.unlock();
			}
		}
		renderer.sectionOcclusionGraph().update(
				cameraState, this.optionsRenderState.fov, this.levelRenderState.chunkLoadingRenderState);
	}
}
