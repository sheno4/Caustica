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
        assertEquals(2.0 * 0.25 * 10.0, light.peakLuminousIntensityCandela(), 1.0e-6);
        assertEquals(1.0, light.orientation().axisZ(), 0.0);
        assertEquals(0.0, light.orientation().halfAngleRadians(), 0.0);
    }

    /**
     * The proposal ranks every shape by peak intensity over squared distance, so each shape's intensity
     * must be the illuminance it actually delivers along its brightest direction at one metre. Getting
     * this wrong does not bias the estimator; it silently mis-ranks whole classes of light against each
     * other and shows up only as noise.
     */
    @Test
    void peakIntensityIsPowerDividedByEachShapeEmittedSolidAngle() {
        FiniteLight rectangle = FiniteLight.from(new LightDescriptor.Rectangle(1, 0, 0, 0,
                1, 0, 0, 0, 1, 0, 0, 0, 1, 3, 3, 3), 1.0);
        assertEquals(rectangle.luminousPowerLumens() / Math.PI,
                rectangle.peakLuminousIntensityCandela(), 1.0e-9);

        FiniteLight point = FiniteLight.from(new LightDescriptor.Point(2, 0, 0, 0,
                5, 3, 3, 3), 1.0);
        assertEquals(point.luminousPowerLumens() / (4.0 * Math.PI),
                point.peakLuminousIntensityCandela(), 1.0e-9);

        double halfAngle = Math.PI / 5.0;
        FiniteLight spot = FiniteLight.from(new LightDescriptor.Spot(3, 0, 0, 0,
                0, 0, 1, 5, halfAngle, 3, 3, 3), 1.0);
        double solidAngle = 2.0 * Math.PI * (1.0 - Math.cos(halfAngle));
        assertEquals(spot.luminousPowerLumens() / solidAngle,
                spot.peakLuminousIntensityCandela(), 1.0e-9);
    }

    @Test
    void onlyRectanglePhotometryDependsOnWorldScale() {
        var point = new LightDescriptor.Point(1, 0, 0, 0, 5, 3, 3, 3);
        assertEquals(FiniteLight.from(point, 1.0).peakLuminousIntensityCandela(),
                FiniteLight.from(point, 4.0).peakLuminousIntensityCandela(), 0.0);

        var rectangle = new LightDescriptor.Rectangle(2, 0, 0, 0,
                1, 0, 0, 0, 1, 0, 0, 0, 1, 3, 3, 3);
        assertEquals(16.0 * FiniteLight.from(rectangle, 1.0).peakLuminousIntensityCandela(),
                FiniteLight.from(rectangle, 4.0).peakLuminousIntensityCandela(), 1.0e-9);
    }

    @Test
    void pointRangeConvertsFromMetersAndSpotPowerUsesConeSolidAngle() {
        FiniteLight point = FiniteLight.from(new LightDescriptor.Point(1, 0, 0, 0,
                6, 2, 2, 2), 2.0);
        assertEquals(0.0, point.bounds().minX());
        assertEquals(0.0, point.bounds().maxX());
        assertEquals(8.0 * Math.PI, point.luminousPowerLumens(), 1.0e-6);

        FiniteLight spot = FiniteLight.from(new LightDescriptor.Spot(2, 0, 0, 0,
                0, 0, -2, 8, Math.PI / 3.0, 4, 4, 4), 2.0);
        assertEquals(0.0, spot.bounds().minZ());
        assertEquals(0.0, spot.bounds().maxZ());
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

    @Test
    void rectangleNormalMustDescribeItsActualEmitterPlane() {
        assertThrows(IllegalArgumentException.class, () -> FiniteLight.from(
                new LightDescriptor.Rectangle(1, 0, 0, 0,
                        1, 0, 0, 0, 1, 0,
                        0, 1, 0, 1, 1, 1), 1.0));
    }
}
