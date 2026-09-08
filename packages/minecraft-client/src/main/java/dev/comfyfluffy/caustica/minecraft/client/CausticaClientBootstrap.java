package dev.comfyfluffy.caustica.minecraft.client;

import net.minecraft.client.Minecraft;

/** Shared client initialization used by each loader entrypoint. */
public final class CausticaClientBootstrap {
    private CausticaClientBootstrap() {
    }

    public static void initialize() {
        CausticaMod.LOGGER.info("Caustica client initialized");
        var composition = CausticaClientComposition.current();
        composition.runtime().installHost(composition.runtimeHost());
        MinecraftDebugService.start(Minecraft.getInstance());
        RtExposureDebugEntry.register();
    }
}
