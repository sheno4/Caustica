package dev.comfyfluffy.caustica.api.pass;

/**
 * A pass that produces something the world ray-tracing pipeline reads, recorded before the trace
 * dispatches. Sky and atmosphere LUTs, a blue-noise table, a per-material parameter buffer, a voxel
 * structure an extension's own shader samples during shading.
 *
 * <p>The renderer knows only that this pass records before the trace. It does not know what the pass
 * computes, what pipelines it built, or what its resources mean. Images reach the extension's Slang
 * through offsets into its descriptor-heap ranges; buffers reach it through device addresses stored in
 * extension-owned data.
 *
 * <p>It records against a bare {@link PassFrame}, and that is the point: at this stage there is nothing to
 * offer. The acceleration structure is not yet consumed, no colour exists, and a pass wanting the camera or
 * the game state reads it from the host itself, as it already must for everything else it gathers. A frame
 * type of its own would have carried only the services every stage has.
 *
 * <p>The callback runs every rendered frame, but a pass whose asynchronously prepared source data has not
 * changed simply records nothing. GPU-visible changes are recorded here so they are ordered before the
 * trace; background workers prepare CPU data and never rewrite memory an in-flight frame may read.
 *
 * <p>Passes record in registration order. Do not depend on it: a pass must not read what another pass
 * wrote earlier in the same frame, because pass selection is a runtime decision and the pass you were
 * counting on may not be active.
 */
public interface WorldResourcePass extends PassLifecycle {
    /** Record this pass's work for one frame. */
    void record(PassFrame frame);
}
