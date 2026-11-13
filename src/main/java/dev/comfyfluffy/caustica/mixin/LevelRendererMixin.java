package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.compat.VivecraftCompat;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 1200)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;
	@Shadow @Final private SubmitNodeStorage submitNodeStorage;
	@Shadow @Final private FeatureRenderDispatcher featureRenderDispatcher;
	@Unique private boolean caustica$deferredEyeOverlays;

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
		this.caustica$deferredEyeOverlays = false;
		if (VivecraftCompat.isVrActive()) {
			var renderer = Minecraft.getInstance().gameRenderer;
			if (VivecraftCompat.view().isEye()
					&& VanillaRenderController.INSTANCE.canCompositeEye()
					&& !renderer.gameRenderState().useShaderTransparency()) {
				VivecraftCompat.deferEyeOverlays(this.levelRenderState);
				this.caustica$deferredEyeOverlays = true;
			}
			return;
		}
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

		VanillaRenderController.INSTANCE.markWorldSkipped();
		ci.cancel();
	}

	/** Runs before Vivecraft's RETURN hook submits its deferred GUI and hands. */
	@Inject(method = "render", at = @At("RETURN"), order = 900)
	private void caustica$compositeVivecraftEye(CallbackInfo ci) {
		if (!this.caustica$deferredEyeOverlays) return;
		this.caustica$deferredEyeOverlays = false;
		try {
			if (WorldRenderScaler.INSTANCE.endEye(Minecraft.getInstance().gameRenderer.mainRenderTarget())) {
				VivecraftCompat.restoreEyeEffects(this.submitNodeStorage, this.levelRenderState, this.featureRenderDispatcher);
			}
		} finally {
			RtComposite.INSTANCE.finishGraphicsUse();
		}
	}
}
