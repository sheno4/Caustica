package dev.comfyfluffy.caustica.engine.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FiniteLightTest {
    @Test
    void rectangleUsesMetricAreaAndPhotometricPower() {
        var descriptor = new LightDescriptor.Rectangle(7, 1, 2, 3,
                1, 0, 0, 0, 0.5, 0, 0, 0, 4, 10, 10, 10);
        FiniteLight light = FiniteLight.from(descriptor, 0.5);

        assertEquals(0.0, light.bounds().minX());
        assertEquals(2.5, light.bounds().maxY());
        assertEquals(Math.PI * 2.0 * 0.25 * 10.0,
                light.luminousPowerLumens(), 1.0e-6);
        assertEquals(1.0, light.orientation().axisZ(), 0.0);
        assertEquals(0.0, light.orientation().halfAngleRadians(), 0.0);
    }

    @Test
    void pointRangeConvertsFromMetersAndSpotPowerUsesConeSolidAngle() {
        FiniteLight point = FiniteLight.from(new LightDescriptor.Point(1, 0, 0, 0,
                6, 2, 2, 2), 2.0);
        assertEquals(-3.0, point.bounds().minX());
        assertEquals(8.0 * Math.PI, point.luminousPowerLumens(), 1.0e-6);

        FiniteLight spot = FiniteLight.from(new LightDescriptor.Spot(2, 0, 0, 0,
                0, 0, -2, 8, Math.PI / 3.0, 4, 4, 4), 2.0);
        assertEquals(-4.0, spot.bounds().minZ());
        assertEquals(4.0 * Math.PI, spot.luminousPowerLumens(), 1.0e-6);
        assertEquals(-1.0, spot.orientation().axisZ(), 0.0);
    }

    @Test
    void spotAngleIsFiniteRadiansWithinOneFullDirectionalCone() {
        assertThrows(IllegalArgumentException.class, () -> FiniteLight.from(
                new LightDescriptor.Spot(1, 0, 0, 0, 0, 0, 1,
                        4, 0.0, 1, 1, 1), 1.0));
        assertThrows(IllegalArgumentException.class, () -> FiniteLight.from(
                new LightDescriptor.Spot(1, 0, 0, 0, 0, 0, 1,
                        4, Math.PI + 0.01, 1, 1, 1), 1.0));
        assertThrows(IllegalArgumentException.class, () -> FiniteLight.from(
                new LightDescriptor.Spot(1, 0, 0, 0, 0, 0, 1,
                        4, Double.NaN, 1, 1, 1), 1.0));
    }
}
