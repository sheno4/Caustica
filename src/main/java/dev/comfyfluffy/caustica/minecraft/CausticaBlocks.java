package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.minecraft.gltf.GltfViewerAnchorBlock;
import dev.comfyfluffy.caustica.minecraft.gltf.GltfViewerAnchorBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;

/** Minecraft blocks used by built-in extension examples. */
public final class CausticaBlocks {
    public static final Identifier GLTF_VIEWER_ANCHOR_ID =
            Identifier.fromNamespaceAndPath("caustica", "gltf_viewer_anchor");
    public static final ResourceKey<Block> GLTF_VIEWER_ANCHOR_KEY =
            ResourceKey.create(Registries.BLOCK, GLTF_VIEWER_ANCHOR_ID);
    public static final ResourceKey<BlockEntityType<?>> GLTF_VIEWER_ANCHOR_BLOCK_ENTITY_KEY =
            ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, GLTF_VIEWER_ANCHOR_ID);

    public static final GltfViewerAnchorBlock GLTF_VIEWER_ANCHOR = new GltfViewerAnchorBlock(
            BlockBehaviour.Properties.of()
            .setId(GLTF_VIEWER_ANCHOR_KEY)
            .strength(1.5f)
            .noOcclusion());
    public static final BlockEntityType<GltfViewerAnchorBlockEntity> GLTF_VIEWER_ANCHOR_BLOCK_ENTITY =
            new BlockEntityType<>(GltfViewerAnchorBlockEntity::new, Set.of(GLTF_VIEWER_ANCHOR));

    private CausticaBlocks() {
    }

    public static void registerBlocks(BlockRegistrar registrar) {
        registrar.register(GLTF_VIEWER_ANCHOR_KEY, GLTF_VIEWER_ANCHOR);
    }

    public static void registerBlockEntities(BlockEntityRegistrar registrar) {
        registrar.register(GLTF_VIEWER_ANCHOR_BLOCK_ENTITY_KEY, GLTF_VIEWER_ANCHOR_BLOCK_ENTITY);
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
