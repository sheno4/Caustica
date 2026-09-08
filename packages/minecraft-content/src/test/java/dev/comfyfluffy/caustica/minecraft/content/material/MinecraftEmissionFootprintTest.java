package dev.comfyfluffy.caustica.minecraft.content.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MinecraftEmissionFootprintTest {
    @Test
    void builderOmitsFootprintsWithoutCoverage() {
        var builder = new MinecraftEmissionFootprint.Builder(4, 2, 2);
        builder.add(0, 0, 1, 1, 1, 0);
        assertNull(builder.build());
    }

    @Test
    void normalizedCoordinatesClampToTheFootprint() {
        var builder = new MinecraftEmissionFootprint.Builder(16, 1, 1);
        builder.add(0, 0, 1, 1, 1, 1);
        var footprint = builder.build();
        assertEquals(16, footprint.resolution());
        assertEquals(0, footprint.sampleIndex(-1));
        assertEquals(0, footprint.sampleIndex(0));
        assertEquals(8, footprint.sampleIndex(0.5f));
        assertEquals(15, footprint.sampleIndex(0.999f));
        assertEquals(15, footprint.sampleIndex(1));
        assertEquals(15, footprint.sampleIndex(2));
    }

    @Test
    void oneTexelCoversTheWholeFootprint() {
        var builder = new MinecraftEmissionFootprint.Builder(16, 1, 1);
        builder.add(0, 0, 0.25f, 0.5f, 1, 0.75f);
        var footprint = builder.build();
        assertNotNull(footprint);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            assertEquals(0.25f, footprint.r(x, y));
            assertEquals(0.5f, footprint.g(x, y));
            assertEquals(1, footprint.b(x, y));
            assertEquals(0.75f, footprint.weight(x, y));
        }
    }

    @Test
    void fractionalTexelBoundariesPreserveEmissionArea() {
        var builder = new MinecraftEmissionFootprint.Builder(2, 3, 1);
        builder.add(0, 0, 0, 0, 0, 0);
        builder.add(1, 0, 1, 0.5f, 0.25f, 1);
        builder.add(2, 0, 0, 0, 0, 0);
        var footprint = builder.build();
        assertNotNull(footprint);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
            assertEquals(1f / 3, footprint.r(x, y), 1e-6f);
            assertEquals(1f / 6, footprint.g(x, y), 1e-6f);
            assertEquals(1f / 12, footprint.b(x, y), 1e-6f);
            assertEquals(1f / 3, footprint.weight(x, y), 1e-6f);
        }
    }
}
