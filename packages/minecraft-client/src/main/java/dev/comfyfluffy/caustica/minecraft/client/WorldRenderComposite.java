package dev.comfyfluffy.caustica.minecraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;

/**
 * Brackets level rendering and composites the reconstructed RT image before the hand and HUD.
 * The renderer owns reconstruction; this host hook only selects the destination and reports whether
 * RT supplied the replacement image after vanilla world rendering was skipped.
 */
public final class WorldRenderComposite {
	private final VanillaRenderController renderController;
	// Tracks that the level-render window is open so the safety-net end() does not composite twice.
	private boolean rtWindowOpen;

	public WorldRenderComposite(VanillaRenderController renderController) {
		this.renderController = java.util.Objects.requireNonNull(renderController, "renderController");
	}

	/** Open the level-render window. Called right before level rendering. */
	public void begin(RenderTarget mainTarget) {
		try (var ignored = CausticaClientComposition.current().runtime().profileStage("host.worldBegin")) {
			renderController.beginFrame(mainTarget);
			rtWindowOpen = renderController.shouldCompositeRt();
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

	private void end(RenderTarget mainTarget, boolean beforeHandSeam) {
		if (this.rtWindowOpen) {
			this.rtWindowOpen = false;
			if (!beforeHandSeam && renderController.wasWorldSkippedThisFrame()) {
				renderController.markMissedBeforeHandSeam();
				return;
			}
			long image = mainTarget.getColorTexture() instanceof VulkanGpuTexture texture ? texture.vkImage() : 0L;
			boolean success = image != 0L
					&& CausticaClientComposition.current().runtime().composite(image, mainTarget.width, mainTarget.height);
			renderController.markRtFrameResult(success);
		}
	}

	public void destroy() {
		this.rtWindowOpen = false;
	}
}
