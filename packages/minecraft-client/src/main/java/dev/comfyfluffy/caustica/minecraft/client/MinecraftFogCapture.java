package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFogFrame;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** Captures a bounded number of loaded columns per render frame and publishes only complete grids. */
final class MinecraftFogCapture {
    static final int WIDTH = 33;
    static final int SPACING = 32;
    static final int RADIUS = (WIDTH - 1) * SPACING / 2;
    static final int COLUMNS_PER_FRAME = 32;
    private static final int CENTER_SPACING = SPACING * 2;
    private ClientLevel level;
    private MinecraftFogFrame.Grid published;
    private int originX;
    private int originY;
    private int originZ;
    private int height;
    private int nextColumn;
    private float[] coefficients;
    private float[] terrainHeights;

    MinecraftFogFrame.Grid capture(ClientLevel current, double cameraX, double cameraZ) {
        if (level != current) {
            clear();
            level = current;
        }
        if (coefficients == null) {
            originX = captureOrigin(cameraX);
            originZ = captureOrigin(cameraZ);
            originY = level.getMinY();
            height = (level.getHeight() + SPACING - 1) / SPACING + 1;
            coefficients = new float[WIDTH * WIDTH * height * MinecraftFogFrame.Grid.COMPONENTS];
            terrainHeights = new float[WIDTH * WIDTH];
            nextColumn = 0;
        }
        int end = Math.min(nextColumn + COLUMNS_PER_FRAME, WIDTH * WIDTH);
        for (; nextColumn < end; nextColumn++) captureColumn(nextColumn);
        if (nextColumn == WIDTH * WIDTH) {
            published = new MinecraftFogFrame.Grid(originX, originY, originZ, SPACING,
                    WIDTH, height, WIDTH, coefficients, terrainHeights);
            coefficients = null;
            terrainHeights = null;
        }
        return published;
    }

    /** Nearest aligned center bounds the initial camera offset to half the center spacing. */
    static int captureOrigin(double cameraCoordinate) {
        return (int) (Math.round(cameraCoordinate / CENTER_SPACING) * CENTER_SPACING) - RADIUS;
    }

    void clear() {
        level = null;
        published = null;
        coefficients = null;
        terrainHeights = null;
    }

    private void captureColumn(int column) {
        int x = originX + column % WIDTH * SPACING;
        int z = originZ + column / WIDTH * SPACING;
        var chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return;
        terrainHeights[column] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        var position = new BlockPos.MutableBlockPos();
        for (int y = 0; y < height; y++) {
            int worldY = originY + y * SPACING;
            Holder<Biome> biome = chunk.getNoiseBiome(x >> 2, worldY >> 2, z >> 2);
            int index = (column + WIDTH * WIDTH * y) * MinecraftFogFrame.Grid.COMPONENTS;
            coefficients[index] = density(biome);
            coefficients[index + 1] = humidity(biome);
            coefficients[index + 2] = level.getBrightness(LightLayer.SKY, position.set(x, worldY, z)) / 15.0f;
        }
    }

    private static float density(Holder<Biome> biome) {
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) return 1.8f;
        if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS)) return 0.18f;
        if (biome.is(BiomeTags.IS_JUNGLE)) return 1.45f;
        if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA)) return 1.15f;
        if (biome.is(BiomeTags.IS_SAVANNA)) return 0.45f;
        return 0.8f;
    }

    private static float humidity(Holder<Biome> biome) {
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)
                || biome.is(BiomeTags.IS_JUNGLE)) return 0.95f;
        if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS)) return 0.1f;
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) return 0.8f;
        if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA)) return 0.65f;
        if (biome.is(BiomeTags.IS_SAVANNA)) return 0.25f;
        return 0.45f;
    }
}
