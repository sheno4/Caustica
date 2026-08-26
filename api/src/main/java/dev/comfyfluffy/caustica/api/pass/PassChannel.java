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
 * <p>Registration outlives a render session. A pass added before there is one starts recording when the
 * next one begins, and a pass stays registered across sessions — nothing tells an extension a session
 * began, so one discarded with the last session could never come back. There is no activation callback:
 * a pass allocates what it needs the first time it records, comparing what it has against what the frame
 * says it needs.
 *
 * <p>Removing runs {@link PassLifecycle#destroy} once the last frame that recorded the pass has completed,
 * and so does the session ending.
 *
 * <p>Each kind has its own method rather than one overloaded {@code add}. Which interface a pass implements
 * <em>is</em> where in the frame it records, so a class implementing two of them is contributing two
 * different things; an overload set would make that call ambiguous instead of making it say which one it
 * meant.
 */
public interface PassChannel {
    /** Record before the trace. Order between world-resource passes is meaningless; do not depend on it. */
    void addWorldResourcePass(WorldResourcePass pass);

    /**
     * Record after reconstruction, at the end of the chain the anchor names. A scene-referred grade takes
     * {@link PassAnchor#LAST}; a pass wanting the scene image as reconstruction left it takes
     * {@link PassAnchor#FIRST}; everything else takes {@link PassAnchor#MIDDLE}.
     *
     * <p>The anchor has no default, short as {@code MIDDLE} is to write. The chain is the one place a
     * pass's position is visible in the result, so where a pass sits is worth one token of the author's
     * attention rather than something they find out about later.
     */
    void addPostEffectPass(PostEffectPass pass, PassAnchor anchor);

    /** Record after the display transform, into the UI layer. */
    void addUiPass(UiPass pass);

    /**
     * Stop recording {@code pass} and destroy it once the last frame that recorded it has completed.
     *
     * <p>One method for every kind, because unlike adding there is nothing to say: the channel knows what
     * it is recording, and an instance is only ever recording as one thing.
     *
     * @throws IllegalArgumentException if this channel is not recording that pass
     */
    void removePass(PassLifecycle pass);
}
