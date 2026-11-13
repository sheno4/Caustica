package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.compat.VivecraftCompat;
import dev.comfyfluffy.caustica.rt.RtComposite;

/**
 * RT composite seam. Brackets vanilla's level-rendering section in {@code GameRenderer.render}: the
 * world renders at full resolution, then the ray-traced composite (DLSS-RR denoise + upscale, see
 * {@link RtComposite}) runs once at the before-hand seam, before vanilla's pre-GUI depth clear, so the
 * hand and HUD draw at native resolution on top.
 *
 * <p>The RT renderer owns reconstruction through DLSS Ray Reconstruction. With
 * {@code -Dcaustica.rt=false} this is an inert passthrough.
 */
public final class WorldRenderScaler {
	public static final WorldRenderScaler INSTANCE = new WorldRenderScaler();

	// Tracks that the level-render window is open so the safety-net end() does not composite twice.
	private boolean rtWindowOpen;

	private WorldRenderScaler() {
	}

	/** Open the level-render window. Called right before level rendering. */
	public void begin(RenderTarget mainTarget) {
		this.rtWindowOpen = false;
		VanillaRenderController.INSTANCE.beginFrame(mainTarget);
		if (VanillaRenderController.INSTANCE.shouldCompositeRt()
				&& VivecraftCompat.view() != VivecraftCompat.View.AUXILIARY) {
			this.rtWindowOpen = true;
		}
	}

	/**
	 * Run the RT composite once, at the before-hand seam (the safety-net end() then no-ops because the
	 * window is already closed). In cancel-vanilla mode, this is where the skipped world is replaced.
	 */
	public void end(RenderTarget mainTarget) {
		this.end(mainTarget, true);
	}

	public void endSafetyNet(RenderTarget mainTarget) {
		this.end(mainTarget, false);
	}

	/** Consumes this eye's world window before Vivecraft draws the deferred overlays. */
	public boolean endEye(RenderTarget mainTarget) {
		if (!this.rtWindowOpen || !VivecraftCompat.view().isEye()) return false;
		this.rtWindowOpen = false;
		return RtComposite.INSTANCE.composite(mainTarget.getColorTexture(), mainTarget.width, mainTarget.height);
	}

	private void end(RenderTarget mainTarget, boolean beforeHandSeam) {
		if (this.rtWindowOpen) {
			this.rtWindowOpen = false;
			if (VivecraftCompat.isVrActive()) return;
			if (!beforeHandSeam && VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame()) {
				VanillaRenderController.INSTANCE.markMissedBeforeHandSeam();
				return;
			}
			boolean success = RtComposite.INSTANCE.composite(mainTarget.getColorTexture(), mainTarget.width, mainTarget.height);
			VanillaRenderController.INSTANCE.markRtCompositeResult(success);
		}
	}

	public void destroy() {
		this.rtWindowOpen = false;
	}
}
