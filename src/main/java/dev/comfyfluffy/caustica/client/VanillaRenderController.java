package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.RtRuntime;

public final class VanillaRenderController {
	private final RtTerrain terrain;
	private boolean frameStarted;
	private boolean baseReady;
	private boolean projectionCaptured;
	private boolean worldSkipped;
	private boolean failureLatched;
	private boolean loggedActive;
	private boolean loggedWaitingForRtPlayerSection;
	private boolean loggedRtPlayerSectionReady;
	private boolean rtActive = true;
	private Boolean lastLoggedRtActive;
	private String inactiveReason;
	private String lastLoggedInactiveReason;

	public VanillaRenderController(RtTerrain terrain) {
		this.terrain = java.util.Objects.requireNonNull(terrain, "terrain");
	}

	public void beginFrame(RenderTarget mainTarget) {
		this.frameStarted = true;
		this.projectionCaptured = false;
		this.worldSkipped = false;
		this.baseReady = false;
		this.inactiveReason = null;
		this.rtActive = CausticaClientComposition.current().runtime().frameActive();

		if (!Boolean.valueOf(this.rtActive).equals(this.lastLoggedRtActive)) {
			this.lastLoggedRtActive = this.rtActive;
			CausticaMod.LOGGER.info("RT output mode: {}", this.rtActive ? "rt" : "vanilla");
		}

		if (!this.rtActive) {
			return;
		}

		this.inactiveReason = findInactiveReason(mainTarget);
		this.baseReady = this.inactiveReason == null;
		if (this.baseReady) {
			if (!this.loggedActive) {
				this.loggedActive = true;
				CausticaMod.LOGGER.info("Vanilla world rendering cancellation active; using existing RT composite seam");
			}
		} else {
			logInactive(this.inactiveReason);
		}
	}

	public void markProjectionCaptured() {
		this.projectionCaptured = true;
	}

	public boolean shouldCancelLevelRenderer() {
		return this.shouldCancelLevelRenderer(false);
	}

	public boolean shouldCancelLevelRenderer(boolean waitingForRtPlayerSection) {
		if (!this.rtActive) {
			return false;
		}
		if (!this.frameStarted) {
			logInactive("frame controller was not started");
			return false;
		}
		if (!this.baseReady) {
			return false;
		}
		if (!this.projectionCaptured) {
			logInactive("level projection was not captured");
			return false;
		}
		if (waitingForRtPlayerSection && !this.loggedWaitingForRtPlayerSection) {
			this.loggedWaitingForRtPlayerSection = true;
			CausticaMod.LOGGER.info("Keeping vanilla LevelRenderer canceled while waiting for RT player section residency");
		}
		return true;
	}

	public void markRtPlayerSectionReady() {
		if (!this.loggedRtPlayerSectionReady) {
			this.loggedRtPlayerSectionReady = true;
			CausticaMod.LOGGER.info("Satisfied vanilla terrain-load callback from RT player section residency");
		}
	}

	public void markWorldSkipped() {
		this.worldSkipped = true;
	}

	public boolean wasWorldSkippedThisFrame() {
		return this.worldSkipped;
	}

	/**
	 * Whether RT replaced vanilla's world in the most recently rendered frame.
	 *
	 * <p>{@code LevelExtractor.extract} runs before {@link #beginFrame} clears the latch, so callers there
	 * read the previous frame's outcome. That lag is deliberate: extraction work is dropped only once a
	 * frame has actually proven the vanilla world was cancelled, so a fallback to vanilla (failure latch,
	 * resource epoch boundary) costs one frame of missing entities rather than losing them for as long as
	 * the fallback lasts.</p>
	 */
	public boolean replacedVanillaWorldLastFrame() {
		return this.worldSkipped;
	}

	public boolean shouldCompositeRt() {
		return this.rtActive;
	}

	/** Runtime work switch for per-frame RT work. */
	public boolean rtRuntimeWorkRequested() {
		return CausticaClientComposition.current().runtime().frameActive();
	}

	/**
	 * Whether extraction work can be dropped because RT owns the world. Shared by the extraction-skipping
	 * mixins, including the loader-specific ones whose injection points differ.
	 */
	public boolean rtOwnsWorldRendering() {
		return CausticaClientComposition.current().runtime().active() && replacedVanillaWorldLastFrame();
	}

	public void markRtFrameResult(boolean success) {
		if (this.worldSkipped && !success) {
			latchFailure("RT composite did not produce a replacement frame");
		}
	}

	public void markMissedBeforeHandSeam() {
		if (this.worldSkipped) {
			latchFailure("missed before-hand RT composite seam after vanilla world was skipped");
		}
	}

	/** Re-arm vanilla cancellation after an explicit renderer invalidation or runtime mode transition. */
	public void resetFailureLatch() {
		this.failureLatched = false;
		this.baseReady = false;
		this.inactiveReason = null;
		this.lastLoggedInactiveReason = null;
	}

	private String findInactiveReason(RenderTarget mainTarget) {
		RtRuntime.WorldReplacement replacement = CausticaClientComposition.current().runtime().worldReplacement();
		if (this.failureLatched || replacement == RtRuntime.WorldReplacement.RENDERER_FAILED) {
			return "RT composite failure latch is set";
		}
		if (replacement == RtRuntime.WorldReplacement.FRAME_INACTIVE) {
			return "caustica.rt is false";
		}
		if (replacement == RtRuntime.WorldReplacement.DEVICE_UNAVAILABLE) {
			return "RT context is not ready";
		}
		if (terrain.currentOrNull() == null) {
			return "RT terrain is not ready";
		}
		if (replacement == RtRuntime.WorldReplacement.RESOURCE_TRANSITION) {
			return "RT resources are crossing an epoch boundary";
		}
		if (mainTarget == null || mainTarget.getColorTexture() == null || mainTarget.getDepthTexture() == null) {
			return "main render target textures are not ready";
		}
		return null;
	}

	private void latchFailure(String reason) {
		if (!this.failureLatched) {
			CausticaMod.LOGGER.warn("Disabling vanilla world cancellation: {}", reason);
		}
		this.failureLatched = true;
		this.baseReady = false;
		this.inactiveReason = reason;
	}

	private void logInactive(String reason) {
		if (reason == null || reason.equals(this.lastLoggedInactiveReason)) {
			return;
		}
		CausticaMod.LOGGER.info("Vanilla world cancellation inactive: {}", reason);
		this.lastLoggedInactiveReason = reason;
	}
}
