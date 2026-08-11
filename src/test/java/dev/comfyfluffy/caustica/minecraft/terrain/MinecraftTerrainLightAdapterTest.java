package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.engine.light.FiniteLight;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftTerrainLightAdapterTest {
    @Test
    void legacyShadingNormalSelectsTheGeometricRectangleFacing() {
        float[] record = new float[RtLightCollector.FLOATS_PER_LIGHT];
        record[4] = 0.8f;
        record[5] = 0.2f;
        record[6] = -0.4f;
        record[8] = 2.0f;
        record[13] = 3.0f;
        record[16] = 10.0f;
        record[17] = 11.0f;
        record[18] = 12.0f;

        var batch = MinecraftTerrainLightAdapter.describe(4, 2, 3, 5, record);
        LightDescriptor.Rectangle light = (LightDescriptor.Rectangle) batch.lights().getFirst();

        assertEquals(32.0, light.positionX());
        assertEquals(48.0, light.positionY());
        assertEquals(80.0, light.positionZ());
        assertEquals(0.0, light.normalX(), 0.0);
        assertEquals(0.0, light.normalY(), 0.0);
        assertEquals(-6.0, light.normalZ());
        FiniteLight.from(light, MinecraftTerrainLightAdapter.METERS_PER_WORLD_UNIT);
        assertThrows(UnsupportedOperationException.class, () -> batch.lights().add(light));
    }
}
