package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftTerrainLightAdapterTest {
    @Test
    void shadingNormalSelectsTheRectangleAxisWinding() {
        float[] record = new float[MinecraftTerrainLightAdapter.FLOATS_PER_LIGHT];
        record[4] = 0.8f;
        record[5] = 0.2f;
        record[6] = -0.4f;
        record[8] = 2.0f;
        record[13] = 3.0f;
        record[16] = 10.0f;
        record[17] = 11.0f;
        record[18] = 12.0f;

        var batch = MinecraftTerrainLightAdapter.describe(4L, 7L, 32, 48, 80, record);
        LightDescriptor.Rectangle light = (LightDescriptor.Rectangle) batch.lights().getFirst();

        assertEquals(32.0, light.positionX());
        assertEquals(48.0, light.positionY());
        assertEquals(80.0, light.positionZ());
        assertEquals(-3.0, light.halfVy());
        assertEquals(4L, batch.sectionKey());
        assertEquals(7L, batch.revision());
        assertThrows(UnsupportedOperationException.class, () -> batch.lights().add(light));
    }
}
