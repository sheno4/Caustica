package dev.comfyfluffy.caustica.example.gltfviewer;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.function.BiConsumer;

/** Inventory forms for the two world anchors. */
public final class GltfViewerItems {
    public static final ResourceKey<Item> GLTF_ANCHOR_KEY = ResourceKey.create(
            Registries.ITEM, GltfViewerBlocks.GLTF_ANCHOR_ID);
    public static final ResourceKey<Item> PROCEDURAL_SURFACE_KEY = ResourceKey.create(
            Registries.ITEM, GltfViewerBlocks.PROCEDURAL_SURFACE_ID);

    public static final Item GLTF_ANCHOR = new BlockItem(GltfViewerBlocks.GLTF_ANCHOR,
            properties(GLTF_ANCHOR_KEY));
    public static final Item PROCEDURAL_SURFACE = new BlockItem(GltfViewerBlocks.PROCEDURAL_SURFACE,
            properties(PROCEDURAL_SURFACE_KEY));

    private GltfViewerItems() {
    }

    public static void register(BiConsumer<ResourceKey<Item>, Item> registrar) {
        registrar.accept(GLTF_ANCHOR_KEY, GLTF_ANCHOR);
        registrar.accept(PROCEDURAL_SURFACE_KEY, PROCEDURAL_SURFACE);
    }

    private static Item.Properties properties(ResourceKey<Item> key) {
        return new Item.Properties().setId(key).useBlockDescriptionPrefix();
    }

}
