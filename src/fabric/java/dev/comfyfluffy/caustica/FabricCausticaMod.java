package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.minecraft.CausticaItems;
import dev.comfyfluffy.caustica.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.platform.FabricPlatform;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/** Fabric entrypoint for the shared Caustica initialization. */
public final class FabricCausticaMod implements ModInitializer {
    @Override
    public void onInitialize() {
        CausticaPlatform.install(new FabricPlatform());
        CausticaItems.register((key, item) -> Registry.register(BuiltInRegistries.ITEM, key, item));
        CausticaMod.initialize();
    }
}
