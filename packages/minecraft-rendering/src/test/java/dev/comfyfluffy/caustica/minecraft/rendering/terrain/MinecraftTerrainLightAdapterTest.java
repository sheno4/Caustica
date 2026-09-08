package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftTerrainLightAdapterTest {
    @Test void translatesOnlyTheEmitterPosition() {
        var light = new LightDescriptor.Parallelogram(.5, 1, 2, 2, 0, 0, 0, -3, 0, 10, 11, 12);
        var source = new dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainEmitter(light, 12, 2);
        var batch = MinecraftTerrainLightAdapter.describe(4L, 7L, 32, 48, 80, java.util.List.of(source));
        var emitter = batch.emitters().getFirst();
        var placed = emitter.descriptor();
        assertEquals(32.5, placed.positionX());
        assertEquals(49.0, placed.positionY());
        assertEquals(82.0, placed.positionZ());
        assertEquals(light.halfUx(), placed.halfUx());
        assertEquals(light.halfVy(), placed.halfVy());
        assertEquals(light.radianceRedCdM2(), placed.radianceRedCdM2());
        assertEquals(light.radianceGreenCdM2(), placed.radianceGreenCdM2());
        assertEquals(light.radianceBlueCdM2(), placed.radianceBlueCdM2());
        assertEquals(4L, batch.sectionKey());
        assertEquals(7L, batch.revision());
        assertEquals(12, emitter.firstPrimitive());
        assertEquals(2, emitter.primitiveCount());
        assertEquals(.5, source.descriptor().positionX());
        assertThrows(UnsupportedOperationException.class, () -> batch.emitters().add(emitter));
    }
}
