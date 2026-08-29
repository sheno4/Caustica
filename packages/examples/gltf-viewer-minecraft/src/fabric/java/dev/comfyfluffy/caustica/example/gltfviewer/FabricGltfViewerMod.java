package dev.comfyfluffy.caustica.example.gltfviewer;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/** Fabric content-registration entrypoint for the standalone extension. */
public final class FabricGltfViewerMod implements ModInitializer {
    @Override
    public void onInitialize() {
        GltfViewerBlocks.registerBlocks((key, block) ->
                Registry.register(BuiltInRegistries.BLOCK, key, block));
        GltfViewerBlocks.registerBlockEntities((key, blockEntityType) ->
                Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, key, blockEntityType));
        GltfViewerItems.register((key, item) ->
                Registry.register(BuiltInRegistries.ITEM, key, item));
    }
}
