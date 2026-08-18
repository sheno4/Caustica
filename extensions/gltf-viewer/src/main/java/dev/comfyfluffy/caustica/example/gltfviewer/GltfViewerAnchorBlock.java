package dev.comfyfluffy.caustica.example.gltfviewer;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** Invisible world anchor for the bundled glTF viewer scene. */
public final class GltfViewerAnchorBlock extends BaseEntityBlock {
    public static final MapCodec<GltfViewerAnchorBlock> CODEC = simpleCodec(GltfViewerAnchorBlock::new);

    public GltfViewerAnchorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new GltfViewerAnchorBlockEntity(position, state);
    }
}
