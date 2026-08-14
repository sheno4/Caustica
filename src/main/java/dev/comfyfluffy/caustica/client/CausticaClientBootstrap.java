package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.MinecraftRuntimeHost;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;

/** Shared client initialization and lifecycle hooks used by each loader entrypoint. */
public final class CausticaClientBootstrap {
    private CausticaClientBootstrap() {
    }

    public static void initialize() {
        CausticaMod.LOGGER.info("Caustica client initialized");
        RtRuntime.INSTANCE.installHost(MinecraftRuntimeHost.INSTANCE);

        // Class-init runs DebugScreenEntries.register(...) via its ID field; touching the class here
        // makes the entry discoverable in F3's entry list. Off by default -- the player opts in the
        // same way as any other optional vanilla entry (e.g. GPU utilization).
        @SuppressWarnings("unused")
        Object registerExposureDebugEntry = RtExposureDebugEntry.ID;
    }

    public static void invalidateRenderState() {
        RtTerrain.requestFullClear();
        if (RtRuntime.hasSession()) {
            ProviderManager.INSTANCE.invalidateScenes();
        }
        RtComposite.INSTANCE.resetExposureHistory();
        RtComposite.INSTANCE.resetFailureLatch();
        VanillaRenderController.INSTANCE.resetFailureLatch();
    }
}
