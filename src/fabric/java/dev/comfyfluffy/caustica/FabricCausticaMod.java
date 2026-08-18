package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.minecraft.CausticaItems;
import dev.comfyfluffy.caustica.minecraft.CausticaBlocks;
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
        CausticaBlocks.registerBlocks((key, block) -> Registry.register(BuiltInRegistries.BLOCK, key, block));
        CausticaBlocks.registerBlockEntities((key, blockEntityType) ->
                Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, key, blockEntityType));
        CausticaItems.register((key, item) -> Registry.register(BuiltInRegistries.ITEM, key, item));
        CausticaMod.initialize();
    }
}
