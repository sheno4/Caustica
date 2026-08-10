package dev.comfyfluffy.caustica.engine.light;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LightBvhTest {
    @Test
    void nodesMatchRecursiveCpuReference() {
        List<LightDescriptor.Finite> descriptors = List.of(
                rectangle(1, -8, 1, 0, 0, 1),
                rectangle(2, 7, 0, 1, 0, 2),
                rectangle(3, 0, 9, 0, 0, -3),
                rectangle(4, 1, -6, -1, 0, 0));
        LightBvh.Data bvh = LightBvh.build(descriptors, 1.0, () -> false);

        assertEquals(7, bvh.nodes().size());
        Set<Integer> leaves = new HashSet<>();
        Reference root = reference(bvh, bvh.rootIndex(), leaves);
        assertEquals(Set.of(0, 1, 2, 3), leaves);
        assertEquals(root.power, bvh.totalLuminousPowerLumens(), 1.0e-12);
        assertTrue(bvh.maxDepth() >= 3);
    }

    @Test
    void nearAntipodalOrientationMergesStayFiniteAndConservative() {
        LightBvh.OrientationCone merged = new LightBvh.OrientationCone(0, 1, 0, 0.2);
        for (int i = 0; i < 256; i++) {
            double x = (i + 1) * 1.0e-12;
            LightBvh.OrientationCone next = new LightBvh.OrientationCone(
                    (i & 1) == 0 ? x : -x, -1, 0, 0.2);
            LightBvh.OrientationCone previous = merged;
            merged = LightBvh.OrientationCone.union(merged, next);
            assertTrue(Double.isFinite(merged.axisX()));
            assertTrue(Double.isFinite(merged.axisY()));
            assertTrue(Double.isFinite(merged.axisZ()));
            assertTrue(Double.isFinite(merged.halfAngleRadians()));
            assertTrue(merged.contains(previous, 1.0e-10));
            assertTrue(merged.contains(next, 1.0e-10));
        }
    }

    private static Reference reference(LightBvh.Data bvh, int index, Set<Integer> leaves) {
        LightBvh.Node node = bvh.nodes().get(index);
        if (node.isLeaf()) {
            leaves.add(node.lightIndex());
            FiniteLight light = bvh.lights().get(node.lightIndex());
            assertEquals(light.bounds(), node.bounds());
            assertEquals(light.luminousPowerLumens(), node.luminousPowerLumens(), 0.0);
            return new Reference(light.bounds(), light.luminousPowerLumens(), light.orientation());
        }
        Reference left = reference(bvh, node.left(), leaves);
        Reference right = reference(bvh, node.right(), leaves);
        assertEquals(left.bounds.union(right.bounds), node.bounds());
        assertEquals(left.power + right.power, node.luminousPowerLumens(), 1.0e-12);
        assertTrue(node.orientation().contains(left.orientation, 1.0e-10));
        assertTrue(node.orientation().contains(right.orientation, 1.0e-10));
        return new Reference(node.bounds(), node.luminousPowerLumens(), node.orientation());
    }

    private static LightDescriptor.Rectangle rectangle(long key, double x, double y,
                                                       double nx, double ny, double nz) {
        double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= normalLength;
        ny /= normalLength;
        nz /= normalLength;
        double ux = Math.abs(nz) < 0.9 ? ny : 1.0;
        double uy = Math.abs(nz) < 0.9 ? -nx : 0.0;
        double uz = 0.0;
        double uLength = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux = 0.5 * ux / uLength;
        uy = 0.5 * uy / uLength;
        uz = 0.5 * uz / uLength;
        double vx = ny * uz - nz * uy;
        double vy = nz * ux - nx * uz;
        double vz = nx * uy - ny * ux;
        return new LightDescriptor.Rectangle(key, x, y, 0,
                ux, uy, uz, vx, vy, vz, nx, ny, nz, 1, 1, 1);
    }

    private record Reference(LightBvh.Aabb bounds, double power,
                             LightBvh.OrientationCone orientation) {
    }
}
