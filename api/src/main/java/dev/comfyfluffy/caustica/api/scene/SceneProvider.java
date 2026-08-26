package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.ProviderLifecycle;

/**
 * Notifications for a source of retained data. Registering one buys no access — the channels come from
 * {@link CausticaApi} whenever a source wants them, from whatever thread it is on. What registration buys
 * is being told when the facts underneath the data change, and one hook that cannot be reached any other
 * way.
 *
 * <p>Registration through {@link CausticaApi#providers()} is therefore optional. A source that resubmits
 * on its own terms and watches {@link SceneChannel#generation()} needs none of this; a source that wants
 * world-change notification or frame coherence registers.
 *
 * <p>A provider instance is registered for one runtime activation and is finished after removal. Add a
 * fresh instance for another activation.
 */
public interface SceneProvider extends ProviderLifecycle {
    /** Advance source state immediately before frame-cadence data is collected. */
    default void prepareFrame() {
    }

    /**
     * Submit data that must land in <em>this</em> frame, coherent with the camera and scene the frame names
     * — particles, view-dependent geometry. This is the only thing registration makes possible: everything
     * else goes through {@link CausticaApi#geometry()}, which the renderer applies as soon as it can rather
     * than on a frame boundary.
     */
    default void submitGeometry(SceneFrameContext frame) {
    }
}
