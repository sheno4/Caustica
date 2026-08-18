package dev.comfyfluffy.caustica.example.gltfviewer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side index of loaded anchors; the authored glTF asset owns all model transforms. */
public final class GltfViewerAnchorBlockEntity extends BlockEntity {
    private static final Set<GltfViewerAnchorBlockEntity> LOADED = ConcurrentHashMap.newKeySet();

    public GltfViewerAnchorBlockEntity(BlockPos position, BlockState state) {
        super(GltfViewerBlocks.ANCHOR_BLOCK_ENTITY, position, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level.isClientSide()) {
            LOADED.add(this);
        }
    }

    @Override
    public void setRemoved() {
        LOADED.remove(this);
        super.setRemoved();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && level.isClientSide()) {
            LOADED.add(this);
        }
    }

    public static Set<BlockPos> loadedAnchors(Level level, Block anchorBlock) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (GltfViewerAnchorBlockEntity entity : LOADED) {
            if (entity.level == level && !entity.isRemoved() && entity.getBlockState().is(anchorBlock)) {
                positions.add(entity.getBlockPos().immutable());
            }
        }
        return Set.copyOf(positions);
    }
}
