package dev.comfyfluffy.caustica.minecraft.content.material;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MaterialTextureLevelsTest {
    @Test
    void oddTrailingTexelsContributeToEveryPlane() {
        for (int[] extent : List.of(new int[]{3, 1}, new int[]{1, 3}, new int[]{5, 3})) {
            int pixels = extent[0] * extent[1];
            float[] plane = new float[pixels * 4];
            for (int channel = 0; channel < 4; channel++) {
                plane[(pixels - 1) * 4 + channel] = pixels * (channel + 1);
            }
            var levels = MaterialTextureLevels.mipChain(new MaterialTextureLevels.Level(
                    extent[0], extent[1], plane, plane, plane, plane), 10);
            var last = levels.getLast();
            assertEquals(1, last.width());
            assertEquals(1, last.height());
            for (float[] output : List.of(last.surface0(), last.normal(), last.surface1(), last.emissionColor())) {
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(channel + 1, output[channel], 1e-6f);
                }
            }
        }
    }

    @Test
    void fractionalFootprintsShareTheMiddleTexel() {
        float[] plane = new float[5 * 4];
        plane[2 * 4] = 10;
        var mip = MaterialTextureLevels.mipChain(new MaterialTextureLevels.Level(
                5, 1, plane, plane, plane, plane), 1).getLast();
        assertEquals(2, mip.width());
        assertEquals(2, mip.surface0()[0], 1e-6f);
        assertEquals(2, mip.surface0()[4], 1e-6f);
    }
}
