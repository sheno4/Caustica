package dev.comfyfluffy.caustica.minecraft.client.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RtSectionSnapshotsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void cachesCenterAndHaloAndPreservesFullNeighborhoodAtNegativeCoordinates() {
        BlockState[] values = {Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                Blocks.LAVA.defaultBlockState()};
        Object[] sections = new Object[27];
        int minX = -3, minY = -2, minZ = 1;
        for (int sz = 0; sz < 3; sz++) {
            for (int sy = 0; sy < 3; sy++) {
                for (int sx = 0; sx < 3; sx++) {
                    CountingPalette palette = new CountingPalette();
                    sections[sx + sy * 3 + sz * 9] = palette;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                palette.set(x, y, z, expected(values,
                                        (minX + sx) * 16 + x, (minY + sy) * 16 + y,
                                        (minZ + sz) * 16 + z));
                            }
                        }
                    }
                }
            }
        }
        var states = new RtSectionSnapshots.BlockStates(minX, minY, minZ, sections);
        for (int pass = 0; pass < 2; pass++) {
            for (int y = minY * 16; y < (minY + 3) * 16; y++) {
                for (int z = minZ * 16; z < (minZ + 3) * 16; z++) {
                    for (int x = minX * 16; x < (minX + 3) * 16; x++) {
                        assertSame(expected(values, x, y, z), states.get(x, y, z));
                    }
                }
            }
        }
        int reads = Arrays.stream(sections).mapToInt(section -> ((CountingPalette) section).reads).sum();
        assertEquals(2 * 48 * 48 * 48 - 18 * 18 * 18, reads);
    }

    @Test
    void repeatedMeshingNeighborhoodReadsDecodeEachCoordinateOnce() {
        CountingPalette palette = new CountingPalette();
        Object[] sections = new Object[27];
        Arrays.fill(sections, palette);
        var states = new RtSectionSnapshots.BlockStates(-1, -1, -1, sections);
        for (int pass = 0; pass < 8; pass++) {
            for (int y = -1; y <= 16; y++) {
                for (int z = -1; z <= 16; z++) {
                    for (int x = -1; x <= 16; x++) {
                        assertSame(Blocks.AIR.defaultBlockState(), states.get(x, y, z));
                    }
                }
            }
        }
        assertEquals(18 * 18 * 18, palette.reads);
    }

    @Test
    void retainedSnapshotsAndNewRegionsHaveIndependentDecodedStates() {
        var live = new CountingPalette();
        live.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        Object[] firstSections = new Object[27];
        Arrays.fill(firstSections, RtSectionSnapshots.AIR);
        firstSections[13] = live.copy();
        var first = new RtSectionSnapshots.BlockStates(-1, -1, -1, firstSections);
        live.set(0, 0, 0, Blocks.WATER.defaultBlockState());
        Object[] secondSections = firstSections.clone();
        secondSections[13] = live.copy();
        var second = new RtSectionSnapshots.BlockStates(-1, -1, -1, secondSections);
        assertSame(Blocks.STONE.defaultBlockState(), first.get(0, 0, 0));
        assertSame(Blocks.WATER.defaultBlockState(), second.get(0, 0, 0));
        assertSame(Blocks.STONE.defaultBlockState(), first.get(0, 0, 0));
        assertSame(Blocks.AIR.defaultBlockState(), first.get(-1, 0, 0));
        assertSame(Blocks.AIR.defaultBlockState(), first.get(-16, 0, 0));
    }

    private static BlockState expected(BlockState[] values, int x, int y, int z) {
        return values[Math.floorMod(31 * x + 113 * y + 17 * z + x * y, values.length)];
    }

    private static final class CountingPalette extends PalettedContainer<BlockState> {
        private int reads;

        CountingPalette() {
            super(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        }

        @Override
        public BlockState get(int x, int y, int z) {
            reads++;
            return super.get(x, y, z);
        }
    }
}
