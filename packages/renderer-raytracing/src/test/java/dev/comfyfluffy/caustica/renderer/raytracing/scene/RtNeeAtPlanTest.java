package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtNeeAtPlanTest {
    @Test
    void remapsStableIdentitiesAcrossReorderRemovalAndInsertion() {
        var spot = new LightDescriptor.Spot(0, 0, 0, 0, 0, 1, 1, 0.2, 1, 1, 10);
        var previous = List.of(light(11, spot), light(22, spot), light(33, spot));
        var current = List.of(light(33, spot), light(44, spot), light(11, spot));

        RtNeeAtPlan.Plan plan = RtNeeAtPlan.build(current, previous, 1.0);

        assertArrayEquals(new int[]{2, RtNeeAtPlan.NO_LIGHT, 0}, plan.currentToPrevious());
        assertArrayEquals(new int[]{2, RtNeeAtPlan.NO_LIGHT, 0}, plan.previousToCurrent());
        var packed = plan.pack().order(ByteOrder.nativeOrder());
        assertEquals(2, packed.getInt(0));
        assertEquals(2, packed.getInt(4));
        assertEquals(RtNeeAtPlan.NO_LIGHT, packed.getInt(12));
    }

    @Test
    void assignsPositivePhysicalPowerToEveryPublicShape() {
        List<LightDescriptor> descriptors = List.of(
                new LightDescriptor.Rectangle(0, 0, 0, 2, 0, 0, 0, 3, 0, 4, 5, 6),
                new LightDescriptor.Spot(0, 0, 0, 0, 0, 1,
                        10, 0.2, 11, 12, 13),
                new LightDescriptor.Distant(0, 1, 0, 14, 15, 16, 0.4, false));

        descriptors.forEach(descriptor -> assertTrue(RtNeeAtPlan.samplingPower(descriptor, 1.0) > 0.0f));
    }

    @Test
    void convertsRectangleAreaToSquareMetersAndUsesCircularSpotSolidAngle() {
        var rectangle = new LightDescriptor.Rectangle(0, 0, 0,
                2, 0, 0, 0, 3, 0, 4, 5, 6);
        float unitScale = RtNeeAtPlan.samplingPower(rectangle, 1.0);
        assertEquals(unitScale * 4.0f, RtNeeAtPlan.samplingPower(rectangle, 2.0), unitScale * 1.0e-5f);

        double halfAngle = 0.3;
        var circularSpot = new LightDescriptor.Spot(0, 0, 0, 0, 0, 1,
                10, halfAngle, 1, 1, 1);
        double expectedSolidAngle = 2.0 * Math.PI * (1.0 - Math.cos(halfAngle));
        assertEquals(expectedSolidAngle, RtNeeAtPlan.samplingPower(circularSpot, 1.0), 1.0e-6);
        assertEquals(1.0, RtNeeAtPlan.DISTANT_REFERENCE_AREA_M2);
    }

    @Test
    void descriptorRejectsRadiometryThatCannotRemainFiniteOnGpu() {
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Spot(0, 0, 0, 0, 0, 1,
                1, 0.2, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE));
    }

    private static RtRetainedSceneBackend.SceneLight light(long identity, LightDescriptor descriptor) {
        return new RtRetainedSceneBackend.SceneLight(identity, descriptor);
    }
}
