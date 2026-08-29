package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;

/** Shared client initialization and lifecycle hooks used by each loader entrypoint. */
public final class CausticaClientBootstrap {
    private CausticaClientBootstrap() {
    }

    public static void initialize() {
        CausticaMod.LOGGER.info("Caustica client initialized");
        CausticaClientComposition.current().runtime().installHost(CausticaClientComposition.current().runtimeHost());
        CausticaClientComposition.current().runtime().startProcess();

        // Class-init runs DebugScreenEntries.register(...) via its ID field; touching the class here
        // makes the entry discoverable in F3's entry list. Off by default -- the player opts in the
        // same way as any other optional vanilla entry (e.g. GPU utilization).
        @SuppressWarnings("unused")
        Object registerExposureDebugEntry = RtExposureDebugEntry.ID;
    }

    public static void invalidateRenderState() {
        CausticaClientComposition.current().runtime().resetRendererFailure();
        CausticaClientComposition.current().renderController().resetFailureLatch();
    }
}
