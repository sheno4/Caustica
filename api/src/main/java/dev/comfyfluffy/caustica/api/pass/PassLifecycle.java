package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.ResourceId;

/**
 * When a pass's resources live. This is one half of what the pass API is; {@link WorldResourcePass} and
 * {@link PostEffectPass} supply the other half by naming <em>where in the frame</em> a pass records.
 * Neither says anything about what the pass computes — that is the extension's own shader, pipeline, and
 * dispatch, which the renderer never inspects.
 *
 * <p>A pass instance belongs to exactly one runtime activation: the factory creates it when the activation
 * opens and nothing reuses it afterwards. So the instance's own lifetime is the outermost scope, and every
 * callback here marks a boundary strictly inside it:
 *
 * <pre>
 *   activated                                                            deactivated
 *   ├─ displayResized ─────── displayResized ───────────────────────────────┤
 *   └─ resourcePackClosing ── resourcePackApplied ── resourcePackClosing ───┘
 * </pre>
 *
 * <p>The two inner scopes are independent of each other and both may cycle any number of times. Resources
 * that survive everything are allocated in {@link #activated}; resources that depend on the display size or
 * on the active resource pack are allocated in the callback that opens their scope and released in the one
 * that closes it. Nothing is reference-counted for you, so a resource allocated in a scope that never
 * closes lives until {@link #deactivated}.
 *
 * <p><b>These callbacks mark when a resource became wrong, never when it became free.</b> A pass told that
 * the display resized is still one or more frames away from the GPU finishing with the images it is about
 * to replace, and nothing here will ever tell it that moment arrived. Release through
 * {@link dev.comfyfluffy.caustica.api.gpu.GpuDevice#retireAfterUse} — the API's single resource-lifetime
 * primitive — everywhere except {@link #deactivated}, the one callback that runs with the device already
 * idle.
 *
 * <p>A pass that throws from any callback is disabled with a logged error, anything it published is
 * unpublished in the same step, and {@link #deactivated} is invoked so it can still clean up. The frame
 * loop continues without it.
 *
 * @param <S> the setup this kind of pass receives — see {@link PassSetup}
 */
public interface PassLifecycle<S extends PassSetup> {
    /** Process-stable identity, matching the id this pass was registered under. */
    ResourceId id();

    /** The activation opened. Allocate anything that outlives every inner scope. */
    default void activated(S setup) {
    }

    /**
     * The display resolution changed, and {@code setup} reports the new one. Called after
     * {@link #activated} and before the first frame at that size. Free the previous size's resources here
     * — this is the close and the open of the display scope in one call, because there is never a moment
     * between them worth observing.
     */
    default void displayResized(S setup) {
    }

    /**
     * The active resource pack is being detached. Anything derived from it — an uploaded texture, a
     * compiled variant, a cached lookup — is invalid after this returns and must be released here.
     */
    default void resourcePackClosing() {
    }

    /** A replacement resource pack became active. Rebuild whatever {@link #resourcePackClosing} released. */
    default void resourcePackApplied(S setup) {
    }

    /**
     * The activation closed: this pass was deselected, the render session ended, or the engine is shutting
     * down. The device is idle, so everything this pass still owns can be destroyed unconditionally. The
     * instance is not reused.
     */
    default void deactivated() {
    }
}
