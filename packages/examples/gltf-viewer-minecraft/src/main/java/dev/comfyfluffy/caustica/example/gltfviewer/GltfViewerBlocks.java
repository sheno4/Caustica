package dev.comfyfluffy.caustica.example.gltfviewer;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;

/** World anchors registered by the standalone viewer mod. */
public final class GltfViewerBlocks {
    public static final Identifier GLTF_ANCHOR_ID = id("gltf_viewer_anchor");
    public static final Identifier PROCEDURAL_SURFACE_ID = id("procedural_surface_anchor");

    public static final ResourceKey<Block> GLTF_ANCHOR_KEY = blockKey(GLTF_ANCHOR_ID);
    public static final ResourceKey<Block> PROCEDURAL_SURFACE_KEY = blockKey(PROCEDURAL_SURFACE_ID);
    public static final ResourceKey<BlockEntityType<?>> ANCHOR_BLOCK_ENTITY_KEY = ResourceKey.create(
            Registries.BLOCK_ENTITY_TYPE, id("viewer_anchor"));

    public static final GltfViewerAnchorBlock GLTF_ANCHOR = anchor(GLTF_ANCHOR_KEY);
    public static final GltfViewerAnchorBlock PROCEDURAL_SURFACE = anchor(PROCEDURAL_SURFACE_KEY);
    public static final BlockEntityType<GltfViewerAnchorBlockEntity> ANCHOR_BLOCK_ENTITY =
            new BlockEntityType<>(GltfViewerAnchorBlockEntity::new, Set.of(GLTF_ANCHOR, PROCEDURAL_SURFACE));

    private GltfViewerBlocks() {
    }

    public static void registerBlocks(BlockRegistrar registrar) {
        registrar.register(GLTF_ANCHOR_KEY, GLTF_ANCHOR);
        registrar.register(PROCEDURAL_SURFACE_KEY, PROCEDURAL_SURFACE);
    }

    public static void registerBlockEntities(BlockEntityRegistrar registrar) {
        registrar.register(ANCHOR_BLOCK_ENTITY_KEY, ANCHOR_BLOCK_ENTITY);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(GltfViewerMod.MOD_ID, path);
    }

    private static ResourceKey<Block> blockKey(Identifier id) {
        return ResourceKey.create(Registries.BLOCK, id);
    }

    private static GltfViewerAnchorBlock anchor(ResourceKey<Block> key) {
        return new GltfViewerAnchorBlock(BlockBehaviour.Properties.of()
                .setId(key)
                .strength(1.5f)
                .noOcclusion());
    }

    @FunctionalInterface
    public interface BlockRegistrar {
        void register(ResourceKey<Block> key, Block block);
    }

    @FunctionalInterface
    public interface BlockEntityRegistrar {
        void register(ResourceKey<BlockEntityType<?>> key, BlockEntityType<?> blockEntityType);
    }
}
