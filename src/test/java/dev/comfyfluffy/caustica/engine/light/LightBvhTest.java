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
        List<LightDescriptor> descriptors = List.of(
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
        return new LightDescriptor.Rectangle(key, x, y, 0,
                0.5, 0, 0, 0, 0.5, 0, nx, ny, nz, 1, 1, 1);
    }

    private record Reference(LightBvh.Aabb bounds, double power,
                             LightBvh.OrientationCone orientation) {
    }
}
