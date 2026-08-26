package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.ui.UiPass;

/**
 * The passes currently recording into a frame, reached from {@link CausticaApi#passes()}.
 *
 * <p>An extension constructs its own pass and hands over the instance. There is no factory and no
 * engine-created context: whatever two of your passes need to share, you already have a place to put it,
 * because you built them both.
 *
 * <p>Adding a pass runs {@link PassLifecycle#activated} against the current epoch before it records
 * anything, so a pass added mid-session is indistinguishable from one added at startup. Removing it runs
 * {@link PassLifecycle#deactivated} once the last frame that recorded it has completed — the deferred
 * destroy every other resource gets — and the instance is finished: add a fresh one rather than the same
 * object again, which is what keeps that final callback an unconditional destroy.
 */
public interface PassChannel {
    /** Record before the trace. Order between world-resource passes is meaningless; do not depend on it. */
    void add(WorldResourcePass pass);

    /** Record after reconstruction, in the chain, with no opinion about where. */
    void add(PostEffectPass pass);

    /**
     * Record in the chain at the end the anchor names. A scene-referred grade takes
     * {@link PassAnchor#LAST}; a pass wanting the scene image as reconstruction left it takes
     * {@link PassAnchor#FIRST}.
     */
    void add(PostEffectPass pass, PassAnchor anchor);

    /** Record after the display transform, into the UI layer. */
    void add(UiPass pass);

    /**
     * Stop recording {@code pass} and destroy it once the last frame that recorded it has completed.
     *
     * @throws IllegalArgumentException if this channel is not recording that pass
     */
    void remove(PassLifecycle<?> pass);
}
