package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftResourceIds;
import dev.comfyfluffy.caustica.settings.ResourceId;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import static dev.comfyfluffy.caustica.minecraft.client.terrain.MinecraftFluidSurface.neighborOccludesFace;

/**
 * Emits fluid interfaces with corner-height averaging and flow-oriented atlas UVs. Each face selects
 * its sprite's material before emitting vertices so its material maps use the same atlas transform.
 * Faces have one winding because rays intersect both sides. Neighbour occlusion uses real block
 * shapes, including glass, and covered cells reach full height to avoid spurious medium interfaces.
 * Coordinates have no raster depth bias. The capture computes geometric normals and water tint.
 */
final class RtFluidMesher {
    /** Each face begins with its sprite material and emits four vertices in winding order. */
    interface Output {
        void beginFace(ResourceId material);
        void vertex(float x, float y, float z, int color, float u, float v);
    }

    private RtFluidMesher() {
    }

    private static boolean isNeighborSameFluid(FluidState fluidState, FluidState neighborFluidState) {
        return neighborFluidState.getType().isSame(fluidState.getType());
    }

    /** Tests whether the block sharing this fluid cell occludes the requested fluid face. */
    private static boolean isFaceOccludedBySelf(BlockState state, Direction direction) {
        VoxelShape occluder = state.getFaceOcclusionShape(direction);
        if (occluder == Shapes.empty()) {
            return false;
        } else if (occluder == Shapes.block()) {
            return true;
        } else {
            VoxelShape shape = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
            return Shapes.blockOccludes(shape, occluder, direction.getOpposite());
        }
    }

    private static boolean shouldRenderFace(FluidState fluidState, BlockState blockState, Direction direction,
                                            FluidState neighborFluidState) {
        return !isNeighborSameFluid(fluidState, neighborFluidState) && !isFaceOccludedBySelf(blockState, direction);
    }

    static void tesselate(BlockAndTintGetter level, BlockPos pos, Output output,
                          FluidStateModelSet fluidModels, BlockState blockState, FluidState fluidState) {
        BlockPos posDown = pos.below();
        BlockState blockStateDown = level.getBlockState(posDown);
        FluidState fluidStateDown = blockStateDown.getFluidState();
        BlockPos posUp = pos.above();
        BlockState blockStateUp = level.getBlockState(posUp);
        FluidState fluidStateUp = blockStateUp.getFluidState();
        BlockState blockStateNorth = level.getBlockState(pos.north());
        FluidState fluidStateNorth = blockStateNorth.getFluidState();
        BlockState blockStateSouth = level.getBlockState(pos.south());
        FluidState fluidStateSouth = blockStateSouth.getFluidState();
        BlockState blockStateWest = level.getBlockState(pos.west());
        FluidState fluidStateWest = blockStateWest.getFluidState();
        BlockState blockStateEast = level.getBlockState(pos.east());
        FluidState fluidStateEast = blockStateEast.getFluidState();
        boolean renderUp = !isNeighborSameFluid(fluidState, fluidStateUp);
        boolean renderDown = shouldRenderFace(fluidState, blockState, Direction.DOWN, fluidStateDown);
        boolean renderNorth = shouldRenderFace(fluidState, blockState, Direction.NORTH, fluidStateNorth);
        boolean renderSouth = shouldRenderFace(fluidState, blockState, Direction.SOUTH, fluidStateSouth);
        boolean renderWest = shouldRenderFace(fluidState, blockState, Direction.WEST, fluidStateWest);
        boolean renderEast = shouldRenderFace(fluidState, blockState, Direction.EAST, fluidStateEast);
        if (!(renderUp || renderDown || renderEast || renderWest || renderNorth || renderSouth)) {
            return;
        }

        FluidModel model = fluidModels.get(fluidState);
        BlockTintSource tintSource = model.tintSource();
        int tintColor = tintSource != null ? tintSource.colorInWorld(blockState, level, pos) : -1;
        MinecraftFluidSurface.CornerHeights heights = MinecraftFluidSurface.cornerHeights(
                level, pos, blockState, fluidState);
        float heightNorthWest = heights.northWest();
        float heightSouthWest = heights.southWest();
        float heightSouthEast = heights.southEast();
        float heightNorthEast = heights.northEast();

        float x = pos.getX() & 15;
        float y = pos.getY() & 15;
        float z = pos.getZ() & 15;
        if (renderUp && !neighborOccludesFace(level, posUp, blockStateUp, Direction.UP,
                Math.min(Math.min(heightNorthWest, heightSouthWest), Math.min(heightSouthEast, heightNorthEast)))) {
            Vec3 flow = fluidState.getFlow(level, pos);
            float u00;
            float u01;
            float u10;
            float u11;
            float v00;
            float v01;
            float v10;
            float v11;
            TextureAtlasSprite sprite;
            if (flow.x == 0.0 && flow.z == 0.0) {
                TextureAtlasSprite stillSprite = model.stillMaterial().sprite();
                sprite = stillSprite;
                u00 = stillSprite.getU0();
                v00 = stillSprite.getV0();
                u01 = u00;
                v01 = stillSprite.getV1();
                u10 = stillSprite.getU1();
                v10 = v01;
                u11 = u10;
                v11 = v00;
            } else {
                float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2);
                float s = Mth.sin(angle) * 0.25F;
                float c = Mth.cos(angle) * 0.25F;
                TextureAtlasSprite flowingSprite = model.flowingMaterial().sprite();
                sprite = flowingSprite;
                u00 = flowingSprite.getU(0.5F + (-c - s));
                v00 = flowingSprite.getV(0.5F + (-c + s));
                u01 = flowingSprite.getU(0.5F + (-c + s));
                v01 = flowingSprite.getV(0.5F + (c + s));
                u10 = flowingSprite.getU(0.5F + (c + s));
                v10 = flowingSprite.getV(0.5F + (c - s));
                u11 = flowingSprite.getU(0.5F + (c - s));
                v11 = flowingSprite.getV(0.5F + (-c - s));
            }

            addFace(output, MinecraftResourceIds.material(sprite),
                    x + 0.0F, y + heightNorthWest, z + 0.0F, u00, v00,
                    x + 0.0F, y + heightSouthWest, z + 1.0F, u01, v01,
                    x + 1.0F, y + heightSouthEast, z + 1.0F, u10, v10,
                    x + 1.0F, y + heightNorthEast, z + 0.0F, u11, v11,
                    tintColor);
        }

        if (renderDown && !neighborOccludesFace(level, posDown, blockStateDown, Direction.DOWN, 1.0F)) {
            TextureAtlasSprite stillSprite = model.stillMaterial().sprite();
            float u0 = stillSprite.getU0();
            float u1 = stillSprite.getU1();
            float v0 = stillSprite.getV0();
            float v1 = stillSprite.getV1();
            addFace(output, MinecraftResourceIds.material(stillSprite),
                    x, y, z, u0, v0,
                    x + 1.0F, y, z, u1, v0,
                    x + 1.0F, y, z + 1.0F, u1, v1,
                    x, y, z + 1.0F, u0, v1,
                    tintColor);
        }

        for (Direction faceDir : Direction.Plane.HORIZONTAL) {
            float hh0;
            float hh1;
            float x0;
            float z0;
            float x1;
            float z1;
            boolean renderCondition;
            BlockPos neighborPos;
            BlockState neighborState;
            switch (faceDir) {
                case NORTH -> {
                    hh0 = heightNorthWest;
                    hh1 = heightNorthEast;
                    x0 = x;
                    x1 = x + 1.0F;
                    z0 = z;
                    z1 = z;
                    renderCondition = renderNorth;
                    neighborPos = pos.north();
                    neighborState = blockStateNorth;
                }
                case SOUTH -> {
                    hh0 = heightSouthEast;
                    hh1 = heightSouthWest;
                    x0 = x + 1.0F;
                    x1 = x;
                    z0 = z + 1.0F;
                    z1 = z + 1.0F;
                    renderCondition = renderSouth;
                    neighborPos = pos.south();
                    neighborState = blockStateSouth;
                }
                case WEST -> {
                    hh0 = heightSouthWest;
                    hh1 = heightNorthWest;
                    x0 = x;
                    x1 = x;
                    z0 = z + 1.0F;
                    z1 = z;
                    renderCondition = renderWest;
                    neighborPos = pos.west();
                    neighborState = blockStateWest;
                }
                case EAST -> {
                    hh0 = heightNorthEast;
                    hh1 = heightSouthEast;
                    x0 = x + 1.0F;
                    x1 = x + 1.0F;
                    z0 = z;
                    z1 = z + 1.0F;
                    renderCondition = renderEast;
                    neighborPos = pos.east();
                    neighborState = blockStateEast;
                }
                default -> throw new UnsupportedOperationException();
            }

            if (renderCondition && !neighborOccludesFace(level, neighborPos, neighborState, faceDir, Math.max(hh0, hh1))) {
                TextureAtlasSprite sprite = model.flowingMaterial().sprite();
                float u0 = sprite.getU(0.0F);
                float u1 = sprite.getU(0.5F);
                float v01 = sprite.getV((1.0F - hh0) * 0.5F);
                float v02 = sprite.getV((1.0F - hh1) * 0.5F);
                float v1 = sprite.getV(0.5F);
                addFace(output, MinecraftResourceIds.material(sprite),
                        x0, y + hh0, z0, u0, v01,
                        x1, y + hh1, z1, u1, v02,
                        x1, y, z1, u1, v1,
                        x0, y, z0, u0, v1,
                        tintColor);
            }
        }
    }

    private static void addFace(Output output, ResourceId material,
                                float x0, float y0, float z0, float u0, float v0,
                                float x1, float y1, float z1, float u1, float v1,
                                float x2, float y2, float z2, float u2, float v2,
                                float x3, float y3, float z3, float u3, float v3,
                                int color) {
        output.beginFace(material);
        output.vertex(x0, y0, z0, color, u0, v0);
        output.vertex(x1, y1, z1, color, u1, v1);
        output.vertex(x2, y2, z2, color, u2, v2);
        output.vertex(x3, y3, z3, color, u3, v3);
    }

}
