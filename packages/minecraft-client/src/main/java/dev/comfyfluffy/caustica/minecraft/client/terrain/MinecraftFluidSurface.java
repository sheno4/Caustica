package dev.comfyfluffy.caustica.minecraft.client.terrain;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Computes the fluid surface shared by terrain geometry and camera-medium selection. */
public final class MinecraftFluidSurface {
    private MinecraftFluidSurface() {
    }

    /** Tests camera containment against the mesh's NW-SW-SE and NW-SE-NE surface triangles. */
    public static boolean contains(BlockAndTintGetter level, BlockPos pos, BlockState blockState,
                                   FluidState fluidState, double cameraX, double cameraY, double cameraZ) {
        return cornerHeights(level, pos, blockState, fluidState).contains(
                cameraY, pos.getY(), cameraX - pos.getX(), cameraZ - pos.getZ());
    }

    static CornerHeights cornerHeights(BlockAndTintGetter level, BlockPos pos, BlockState blockState,
                                       FluidState fluidState) {
        Fluid type = fluidState.getType();
        float heightSelf = cellHeight(level, type, pos, blockState, fluidState);
        if (heightSelf >= 1.0F) {
            return CornerHeights.full();
        }

        BlockPos northPos = pos.north();
        BlockPos southPos = pos.south();
        BlockPos eastPos = pos.east();
        BlockPos westPos = pos.west();
        float heightNorth = cellHeight(level, type, northPos);
        float heightSouth = cellHeight(level, type, southPos);
        float heightEast = cellHeight(level, type, eastPos);
        float heightWest = cellHeight(level, type, westPos);
        return new CornerHeights(
                averageCornerHeight(level, type, heightSelf, heightNorth, heightWest,
                        northPos.relative(Direction.WEST)),
                averageCornerHeight(level, type, heightSelf, heightSouth, heightWest,
                        southPos.relative(Direction.WEST)),
                averageCornerHeight(level, type, heightSelf, heightSouth, heightEast,
                        southPos.relative(Direction.EAST)),
                averageCornerHeight(level, type, heightSelf, heightNorth, heightEast,
                        northPos.relative(Direction.EAST)));
    }

    static boolean neighborOccludesFace(BlockAndTintGetter level, BlockPos neighborPos,
                                        BlockState neighborState, Direction towardNeighbor, float faceHeight) {
        VoxelShape neighborShape = neighborState.getShape(level, neighborPos);
        if (neighborShape.isEmpty()) {
            return false;
        }
        VoxelShape faceShape = Shapes.box(0.0, 0.0, 0.0, 1.0, faceHeight, 1.0);
        return Shapes.blockOccludes(faceShape, neighborShape, towardNeighbor);
    }

    private static float averageCornerHeight(BlockAndTintGetter level, Fluid type, float heightSelf,
                                             float height1, float height2, BlockPos cornerPos) {
        if (height1 >= 1.0F || height2 >= 1.0F) {
            return 1.0F;
        }

        WeightedHeight weightedHeight = new WeightedHeight();
        if (height1 > 0.0F || height2 > 0.0F) {
            float heightCorner = cellHeight(level, type, cornerPos);
            if (heightCorner >= 1.0F) {
                return 1.0F;
            }
            weightedHeight.add(heightCorner);
        }
        weightedHeight.add(heightSelf);
        weightedHeight.add(height1);
        weightedHeight.add(height2);
        return weightedHeight.sum / weightedHeight.weight;
    }

    private static final class WeightedHeight {
        float sum;
        float weight;

        void add(float height) {
            if (height >= 0.8F) {
                sum += height * 10.0F;
                weight += 10.0F;
            } else if (height >= 0.0F) {
                sum += height;
                weight++;
            }
        }
    }

    private static float cellHeight(BlockAndTintGetter level, Fluid fluidType, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return cellHeight(level, fluidType, pos, state, state.getFluidState());
    }

    /** A fluid cell covered by a real-shape occluder is full height, avoiding a phantom top interface. */
    private static float cellHeight(BlockAndTintGetter level, Fluid fluidType, BlockPos pos, BlockState state,
                                    FluidState fluidState) {
        if (!fluidType.isSame(fluidState.getType())) {
            return !state.isSolid() ? 0.0F : -1.0F;
        }
        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        if (fluidType.isSame(aboveState.getFluidState().getType())
                || neighborOccludesFace(level, abovePos, aboveState, Direction.UP, 1.0F)) {
            return 1.0F;
        }
        return fluidState.getOwnHeight();
    }

    record CornerHeights(float northWest, float southWest, float southEast, float northEast) {
        private static final CornerHeights FULL = new CornerHeights(1.0F, 1.0F, 1.0F, 1.0F);

        static CornerHeights full() {
            return FULL;
        }

        float heightAt(double localX, double localZ) {
            if (localZ >= localX) {
                return (float) (northWest + (southEast - southWest) * localX
                        + (southWest - northWest) * localZ);
            }
            return (float) (northWest + (northEast - northWest) * localX
                    + (southEast - northEast) * localZ);
        }

        boolean contains(double cameraY, int blockY, double localX, double localZ) {
            return cameraY < blockY + heightAt(localX, localZ);
        }
    }
}
