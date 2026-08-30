package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.CausticaItems;
import dev.comfyfluffy.caustica.minecraft.client.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.minecraft.client.platform.FabricPlatform;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/** Fabric entrypoint for the shared Caustica initialization. */
public final class FabricCausticaMod implements ModInitializer {
    @Override
    public void onInitialize() {
        CausticaPlatform platform = new FabricPlatform();
        CausticaItems.register((key, item) -> Registry.register(BuiltInRegistries.ITEM, key, item));
        CausticaMod.initialize(platform);
    }
}
