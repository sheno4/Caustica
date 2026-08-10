package dev.comfyfluffy.caustica.engine.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DistantLightTest {
    @Test
    void normalizesDirectionAndPreservesIntegratedIlluminance() {
        var descriptor = new LightDescriptor.Distant(7, 0, 3, 4,
                100_000, 90_000, 80_000, Math.PI / 3.0);
        DistantLight light = DistantLight.from(descriptor);

        assertEquals(0.0, light.directionX());
        assertEquals(0.6, light.directionY(), 1.0e-12);
        assertEquals(0.8, light.directionZ(), 1.0e-12);
        assertEquals(100_000, light.descriptor().illuminanceRedLux());
    }

    @Test
    void rejectsInvalidDirectionPhotometryAndAngularDomain() {
        assertThrows(IllegalArgumentException.class, () -> DistantLight.from(
                new LightDescriptor.Distant(1, 0, 0, 0, 1, 1, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> DistantLight.from(
                new LightDescriptor.Distant(1, 0, 1, 0, -1, 1, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> DistantLight.from(
                new LightDescriptor.Distant(1, 0, 1, 0, 1, 1, 1, Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> DistantLight.from(
                new LightDescriptor.Distant(1, 0, 1, 0, 1, 1, 1, Math.PI * 0.5 + 0.01)));
    }
}
