package dev.comfyfluffy.caustica.api.provider;

public interface LightProvider extends ProviderLifecycle {
    /** Contribute a complete snapshot of this provider's lights for the current frame. */
    default void submitLights(LightSink sink) {
    }

    /** Return the current immutable retained finite-light collection. */
    default RetainedLightCollection retainedLights() {
        return RetainedLightCollection.EMPTY;
    }
}
