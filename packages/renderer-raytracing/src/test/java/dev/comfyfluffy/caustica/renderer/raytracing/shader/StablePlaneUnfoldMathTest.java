package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class StablePlaneUnfoldMathTest {
    @Test
    void reflectedTangentialEndpointMotionSurvivesVirtualUnfolding() {
        Vec planePosition = new Vec(0, 0, 0);
        Vec planeNormal = new Vec(1, 0, 0);
        Vec currentVirtual = reflectPoint(new Vec(-2, 1, 0), planePosition, planeNormal);
        Vec previousVirtual = reflectPoint(new Vec(-2, 2, 0), planePosition, planeNormal);

        assertEquals(2.0, currentVirtual.x, 1.0e-9);
        assertEquals(2.0, previousVirtual.x, 1.0e-9);
        assertNotEquals(currentVirtual.y, previousVirtual.y);
        assertEquals(-1.0, currentVirtual.y - previousVirtual.y, 1.0e-9);
    }

    @Test
    void cameraTranslationProducesNonZeroStableGuideMotion() {
        Vec worldPoint = new Vec(2, 0, 4);
        Vec currentCamera = new Vec(1, 0, 0);
        Vec cameraDelta = new Vec(1, 0, 0);
        Vec currentRelative = worldPoint.minus(currentCamera);
        Vec previousRelative = worldPoint.minus(currentCamera).plus(cameraDelta);

        double currentNdcX = currentRelative.x / currentRelative.z;
        double previousNdcX = previousRelative.x / previousRelative.z;

        assertEquals(0.25, currentNdcX, 1.0e-9);
        assertEquals(0.5, previousNdcX, 1.0e-9);
        assertNotEquals(0.0, previousNdcX - currentNdcX);
    }

    private static Vec reflectPoint(Vec point, Vec planePosition, Vec planeNormal) {
        return point.minus(planeNormal.times(2.0 * point.minus(planePosition).dot(planeNormal)));
    }

    private record Vec(double x, double y, double z) {
        Vec plus(Vec other) { return new Vec(x + other.x, y + other.y, z + other.z); }
        Vec minus(Vec other) { return new Vec(x - other.x, y - other.y, z - other.z); }
        Vec times(double scale) { return new Vec(x * scale, y * scale, z * scale); }
        double dot(Vec other) { return x * other.x + y * other.y + z * other.z; }
    }
}
