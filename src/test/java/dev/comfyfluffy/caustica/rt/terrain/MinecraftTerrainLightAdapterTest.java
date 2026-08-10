package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.engine.light.FiniteLight;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        LightDescriptor.Rectangle light = (LightDescriptor.Rectangle)
                MinecraftTerrainLightAdapter.describe(List.of(
                        new RtLightHierarchy.SectionInput(4, 0, 0, 0, record)),
                        0, 0, 0, () -> false).getFirst();

        assertEquals(0.0, light.normalX(), 0.0);
        assertEquals(0.0, light.normalY(), 0.0);
        assertEquals(-6.0, light.normalZ());
        FiniteLight.from(light, MinecraftTerrainLightAdapter.METERS_PER_WORLD_UNIT);
    }
}
