package dev.comfyfluffy.caustica.minecraft.client;

/** Fabric client entrypoint for shared client initialization. */
public final class CausticaClient implements net.fabricmc.api.ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CausticaClientBootstrap.initialize();
    }
}
