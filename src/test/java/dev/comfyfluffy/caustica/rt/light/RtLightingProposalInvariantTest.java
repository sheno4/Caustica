package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.FiniteLight;
import dev.comfyfluffy.caustica.engine.light.LightBvh;
import dev.comfyfluffy.caustica.api.provider.LightDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host reference for {@code lighting.slang}'s light-selection proposal. The shader cannot be executed
 * here, so the traversal is reimplemented and the invariants that make it a valid proposal are asserted
 * against it: the discrete PDF must sum to one, and no populated stratum may fall below the floor. Both
 * are silent when broken — an unnormalized PDF shifts brightness and a starved stratum only adds noise —
 * so neither shows up in an image comparison until it is severe.
 */
final class RtLightingProposalInvariantTest {
    /** Share reserved for importance weighting after each populated stratum receives its floor. */
    private static final double STRATUM_IMPORTANCE_SHARE = 0.75;

    private static final double SUN_LUX = 100_000.0;
    private static final double BLOCK_EMISSION_CD_M2 = 2000.0;

    @Test
    void proposalIsANormalizedDiscreteDistributionOverEveryStratum() {
        Random random = new Random(20260811L);
        for (int trial = 0; trial < 64; trial++) {
            LightBvh.Data retained = randomScene(random, 1 + random.nextInt(40), 64.0);
            LightBvh.Data frame = randomScene(random, random.nextInt(4), 8.0);
            double[] distant = random.nextBoolean()
                    ? new double[]{SUN_LUX, 4.0} : new double[0];
            Proposal proposal = propose(retained, frame, distant,
                    random.nextDouble() * 128.0 - 64.0, random.nextDouble() * 128.0 - 64.0,
                    random.nextDouble() * 128.0 - 64.0);
            assertEquals(1.0, proposal.total(), 1.0e-9);
        }
    }

    /**
     * A shading point standing among the emitters is inside the root bounds, where there is no nearest-
     * surface distance to measure. Importance there must stay bounded by the node's own extent rather
     * than diverging, or a containing node outranks every other stratum purely because the point happens
     * to be inside its box.
     */
    @Test
    void importanceOfAContainingNodeIsBoundedByItsOwnExtent() {
        LightBvh.Data scene = cluster(24, 20.0);
        LightBvh.Node root = scene.nodes().get(scene.rootIndex());
        double bound = root.peakLuminousIntensityCandela()
                / (0.25 * boundingRadiusSquared(root.bounds()));

        assertTrue(nodeImportance(root, 0.0, 0.0, 0.0) <= bound);
        assertTrue(nodeImportance(root, 1.0, -2.0, 3.0) <= bound);
    }

    /**
     * The regression that made block lights noisier than uniform sampling. A lit room's bounds contain
     * the shading point, so measuring to the nearest point on the bounds gives it zero distance and
     * whatever floor replaces that — while a brighter distant cluster still gets to use its real
     * distance. Ranked that way the far cluster wins, the near emitters stop being proposed, and since
     * they are gated out of direct-hit gathering their light simply goes missing.
     */
    @Test
    void aSurroundingNearClusterOutranksABrighterDistantOne() {
        LightBvh.Data room = shell(8, 8.0, 0.0, 0.0, 0.0);
        LightBvh.Data village = shell(480, 20.0, 100.0, 0.0, 0.0);

        // What each group actually delivers, from the same intensities the proposal ranks by.
        double torch = emitter(0, 0, 0, 0, BLOCK_EMISSION_CD_M2).peakLuminousIntensityCandela();
        double roomLux = 8 * torch / (8.0 * 8.0);
        double villageLux = 480 * torch / (100.0 * 100.0);
        assertTrue(roomLux > villageLux, "fixture no longer models a near-dominant room");

        assertTrue(rootImportance(room, 0.0, 0.0, 0.0) > rootImportance(village, 0.0, 0.0, 0.0),
                "the near cluster lost to a brighter distant one");
    }

    /**
     * Daylight inside a lit build. The sun is one delta source against a whole loaded world of emitters,
     * and direct sunlight on a diffuse surface has no estimator other than this proposal — a specular
     * continuation is the only path that ever sees the disc again.
     */
    @Test
    void sunKeepsMostOfTheProposalInsideALitEmitterCluster() {
        Proposal proposal = propose(cluster(24, 20.0), LightBvh.build(List.of(), () -> false),
                new double[]{SUN_LUX}, 0.0, 0.0, 0.0);

        assertTrue(proposal.distantProbability() > 0.5,
                "sun collapsed to " + proposal.distantProbability());
        assertEquals(1.0, proposal.total(), 1.0e-9);
    }

    /**
     * The same scene at night, and the reason the floor exists in both directions: ranking a world-sized
     * emitter cluster against a delta source by bounds and intensity alone puts one of the two near zero
     * whichever way the bound is taken, so each keeps a guaranteed share instead.
     */
    @Test
    void everyPopulatedStratumKeepsItsFloorShare() {
        double floorShare = (1.0 - STRATUM_IMPORTANCE_SHARE) / 2.0;

        Proposal daylight = propose(cluster(24, 20.0), LightBvh.build(List.of(), () -> false),
                new double[]{SUN_LUX}, 0.0, 0.0, 0.0);
        assertTrue(daylight.retainedProbability() >= floorShare,
                "emitters collapsed to " + daylight.retainedProbability());

        Proposal handheld = propose(cluster(24, 20.0),
                LightBvh.build(List.of(spot(1, 0.0, 1.6, 0.0)), () -> false),
                new double[0], 0.0, 0.0, 2.0);
        assertTrue(handheld.frameProbability() >= floorShare,
                "handheld light collapsed to " + handheld.frameProbability());
    }

    @Test
    void anEmptyStratumSpendsNoneOfTheFloor() {
        Proposal proposal = propose(cluster(4, 6.0), LightBvh.build(List.of(), () -> false),
                new double[0], 0.0, 0.0, 0.0);

        assertEquals(1.0, proposal.retainedProbability(), 1.0e-12);
        assertEquals(0.0, proposal.frameProbability(), 0.0);
        assertEquals(0.0, proposal.distantProbability(), 0.0);
    }

    // ---- Reference implementation of lighting.slang's proposal. -------------------------------------

    /** Mirrors {@code finiteNodeImportance} at unit world scale. */
    private static double nodeImportance(LightBvh.Node node, double x, double y, double z) {
        LightBvh.Aabb bounds = node.bounds();
        double dx = x - 0.5 * (bounds.minX() + bounds.maxX());
        double dy = y - 0.5 * (bounds.minY() + bounds.maxY());
        double dz = z - 0.5 * (bounds.minZ() + bounds.maxZ());
        double distanceSquared = Math.max(dx * dx + dy * dy + dz * dz,
                0.25 * boundingRadiusSquared(bounds));
        return node.peakLuminousIntensityCandela() / Math.max(distanceSquared, 1.0e-6);
    }

    private static double boundingRadiusSquared(LightBvh.Aabb bounds) {
        double rx = 0.5 * (bounds.maxX() - bounds.minX());
        double ry = 0.5 * (bounds.maxY() - bounds.minY());
        double rz = 0.5 * (bounds.maxZ() - bounds.minZ());
        return rx * rx + ry * ry + rz * rz;
    }

    /** Mirrors {@code lightStrata}, then enumerates every root-to-leaf path {@code traverseFinite} can take. */
    private static Proposal propose(LightBvh.Data retained, LightBvh.Data frame, double[] distantLux,
                                    double x, double y, double z) {
        double retainedWeight = rootImportance(retained, x, y, z);
        double frameWeight = rootImportance(frame, x, y, z);
        double distantWeight = 0.0;
        for (double lux : distantLux) distantWeight += lux;

        double totalWeight = retainedWeight + frameWeight + distantWeight;
        if (!(totalWeight > 0.0)) return new Proposal(new double[0], new double[0], new double[0]);

        double populated = (retainedWeight > 0.0 ? 1.0 : 0.0) + (frameWeight > 0.0 ? 1.0 : 0.0)
                + (distantWeight > 0.0 ? 1.0 : 0.0);
        double floorShare = (1.0 - STRATUM_IMPORTANCE_SHARE) / populated;
        double scale = STRATUM_IMPORTANCE_SHARE / totalWeight;

        double[] retainedPdf = descend(retained,
                retainedWeight > 0.0 ? retainedWeight * scale + floorShare : 0.0, x, y, z);
        double[] framePdf = descend(frame,
                frameWeight > 0.0 ? frameWeight * scale + floorShare : 0.0, x, y, z);
        double[] distantPdf = new double[distantLux.length];
        if (distantWeight > 0.0) {
            double stratum = distantWeight * scale + floorShare;
            for (int i = 0; i < distantLux.length; i++) {
                distantPdf[i] = stratum * distantLux[i] / distantWeight;
            }
        }
        return new Proposal(retainedPdf, framePdf, distantPdf);
    }

    private static double rootImportance(LightBvh.Data bvh, double x, double y, double z) {
        return bvh.rootIndex() >= 0
                ? nodeImportance(bvh.nodes().get(bvh.rootIndex()), x, y, z) : 0.0;
    }

    private static double[] descend(LightBvh.Data bvh, double stratumProbability,
                                    double x, double y, double z) {
        double[] pdf = new double[bvh.lights().size()];
        if (bvh.rootIndex() >= 0 && stratumProbability > 0.0) {
            descend(bvh, bvh.rootIndex(), stratumProbability, x, y, z, pdf);
        }
        return pdf;
    }

    private static void descend(LightBvh.Data bvh, int index, double pdf,
                                double x, double y, double z, double[] out) {
        LightBvh.Node node = bvh.nodes().get(index);
        if (node.isLeaf()) {
            out[node.lightIndex()] += pdf;
            return;
        }
        double left = nodeImportance(bvh.nodes().get(node.left()), x, y, z);
        double right = nodeImportance(bvh.nodes().get(node.right()), x, y, z);
        double sum = left + right;
        if (!(sum > 0.0)) return;
        descend(bvh, node.left(), pdf * left / sum, x, y, z, out);
        descend(bvh, node.right(), pdf * right / sum, x, y, z, out);
    }

    private record Proposal(double[] retained, double[] frame, double[] distant) {
        double retainedProbability() { return sum(retained); }

        double frameProbability() { return sum(frame); }

        double distantProbability() { return sum(distant); }

        double total() { return retainedProbability() + frameProbability() + distantProbability(); }

        private static double sum(double[] values) {
            double total = 0.0;
            for (double value : values) total += value;
            return total;
        }
    }

    // ---- Scene fixtures. ---------------------------------------------------------------------------

    /** Emitters bracketing the origin, so any shading point near it is inside the root bounds. */
    private static LightBvh.Data cluster(int count, double radius) {
        return shell(count, radius, 0.0, 0.0, 0.0);
    }

    /** {@code count} emitters spread over a sphere of {@code radius} about the given centre. */
    private static LightBvh.Data shell(int count, double radius,
                                       double centerX, double centerY, double centerZ) {
        ArrayList<FiniteLight> lights = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Fibonacci-ish spiral: even coverage without clumping at the poles.
            double cosPolar = 1.0 - 2.0 * (i + 0.5) / count;
            double sinPolar = Math.sqrt(Math.max(0.0, 1.0 - cosPolar * cosPolar));
            double azimuth = i * 2.399963;
            lights.add(emitter(i,
                    centerX + Math.cos(azimuth) * sinPolar * radius,
                    centerY + cosPolar * radius,
                    centerZ + Math.sin(azimuth) * sinPolar * radius,
                    BLOCK_EMISSION_CD_M2));
        }
        return LightBvh.build(lights, () -> false);
    }

    private static LightBvh.Data randomScene(Random random, int count, double extent) {
        ArrayList<FiniteLight> lights = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lights.add(emitter(i,
                    random.nextDouble() * extent * 2.0 - extent,
                    random.nextDouble() * extent * 2.0 - extent,
                    random.nextDouble() * extent * 2.0 - extent,
                    random.nextDouble() * BLOCK_EMISSION_CD_M2 + 1.0));
        }
        return LightBvh.build(lights, () -> false);
    }

    /** A torch-scale emissive face: 0.2 m square at the configured block-emission luminance. */
    private static FiniteLight emitter(long key, double x, double y, double z, double radiance) {
        return FiniteLight.from(new LightDescriptor.Rectangle(key, x, y, z,
                0.1, 0, 0, 0, 0.1, 0, 0, 0, 1, radiance, radiance, radiance), 1.0);
    }

    private static FiniteLight spot(long key, double x, double y, double z) {
        return FiniteLight.from(new LightDescriptor.Spot(key, x, y, z, 0, 0, 1,
                48.0, Math.toRadians(22.0), 720.0, 690.0, 610.0), 1.0);
    }

}
