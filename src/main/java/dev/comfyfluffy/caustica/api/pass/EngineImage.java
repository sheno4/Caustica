package dev.comfyfluffy.caustica.api.pass;

/**
 * A fixed, engine-declared image slot. Some slots are engine-produced inputs a pass may read
 * ({@link #passOutput()} false); others are pass-produced outputs the engine's own shaders read back
 * through a fixed binding, filled by calling {@code PassSetup.publish} ({@link #passOutput()} true).
 *
 * <p>Format and sizing are no longer declared here: a pass that produces a slot owns creating the image
 * itself (via {@code RtContext.createStorageImage}), so there is nothing left for the engine to allocate
 * on its behalf. Engine-produced slots are sized and formatted by the engine internals that create them.
 */
public enum EngineImage {
    /** Engine-produced: the reconstructed HDR colour target, after DLSS-RR (or the no-RR blit). */
    RECONSTRUCTED_COLOR(false),
    /** Engine-produced: the current frame's scalar exposure value. */
    EXPOSURE(false),
    /** Pass-produced: the bloom pyramid's base level, read back by the look stage. */
    BLOOM(true),
    /** Pass-produced: the per-frame sky-view LUT, read back by {@code sky.slang}'s miss shaders. */
    SKY_VIEW_LUT(true),
    /** Pass-produced: the static atmospheric transmittance LUT. */
    SKY_TRANSMITTANCE_LUT(true);

    private final boolean passOutput;

    EngineImage(boolean passOutput) {
        this.passOutput = passOutput;
    }

    /** True if a pass fills this slot via {@code PassSetup.publish}; false if the engine fills it. */
    public boolean passOutput() {
        return passOutput;
    }
}
