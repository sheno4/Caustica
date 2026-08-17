package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.RetainedLightBatch;

import dev.comfyfluffy.caustica.engine.light.DistantLight;
import dev.comfyfluffy.caustica.engine.light.FiniteLight;
import dev.comfyfluffy.caustica.engine.light.LightBvh;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Builds the source-neutral GPU light records and finite-light BVH used by retained light segments. */
public final class RtRetainedLightSceneBuilder {
    public static final int GPU_FLOATS_PER_LIGHT = 16;
    public static final int GPU_FLOATS_PER_NODE = 12;

    private RtRetainedLightSceneBuilder() {
    }

    public static Data build(List<RetainedLightBatch> batches, double rebaseX, double rebaseY, double rebaseZ,
                             double metersPerWorldUnit, BooleanSupplier cancelled) {
        ArrayList<RetainedLightBatch> ordered = new ArrayList<>(batches);
        ordered.sort(Comparator.comparing((RetainedLightBatch batch) -> batch.source().namespace())
                .thenComparing(batch -> batch.source().path())
                .thenComparingLong(RetainedLightBatch::key));
        ArrayList<LightDescriptor.Finite> descriptors = new ArrayList<>();
        for (int batchIndex = 0; batchIndex < ordered.size(); batchIndex++) {
            if ((batchIndex & 255) == 0) checkCancelled(cancelled);
            for (LightDescriptor.Finite descriptor : ordered.get(batchIndex).lights()) {
                descriptors.add(descriptor);
            }
        }
        return buildFinite(descriptors, rebaseX, rebaseY, rebaseZ, metersPerWorldUnit, cancelled);
    }

    static Data buildFinite(List<? extends LightDescriptor.Finite> descriptors,
                            double rebaseX, double rebaseY, double rebaseZ,
                            double metersPerWorldUnit, BooleanSupplier cancelled) {
        // Canonicalize once. Deriving photometry costs a cross product, square roots and validation per
        // light, and the BVH, the power filter and the record encoding all want the same answer.
        ArrayList<FiniteLight> active = new ArrayList<>(descriptors.size());
        for (int i = 0; i < descriptors.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            FiniteLight light = FiniteLight.from(descriptors.get(i), metersPerWorldUnit);
            if (light.luminousPowerLumens() > 0.0) active.add(light);
        }
        LightBvh.Data bvh = LightBvh.build(active, cancelled);
        float[] lights = new float[Math.multiplyExact(active.size(), GPU_FLOATS_PER_LIGHT)];
        for (int i = 0; i < active.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            encodeFinite(lights, i * GPU_FLOATS_PER_LIGHT, active.get(i),
                    rebaseX, rebaseY, rebaseZ, metersPerWorldUnit);
        }
        float[] nodes = new float[Math.multiplyExact(bvh.nodes().size(), GPU_FLOATS_PER_NODE)];
        for (int i = 0; i < bvh.nodes().size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            encodeNode(nodes, i * GPU_FLOATS_PER_NODE, bvh.nodes().get(i),
                    rebaseX, rebaseY, rebaseZ);
        }
        return new Data(lights, nodes, bvh, active.size(), bvh.rootIndex(),
                rebaseX, rebaseY, rebaseZ, metersPerWorldUnit);
    }

    static void encodeFinite(float[] target, int offset, FiniteLight canonical,
                             double rebaseX, double rebaseY, double rebaseZ,
                             double metersPerWorldUnit) {
        switch (canonical.descriptor()) {
            case LightDescriptor.Rectangle light -> {
                putPosition(target, offset, light, rebaseX, rebaseY, rebaseZ);
                target[offset + 3] = Float.intBitsToFloat(0);
                put3(target, offset + 4, light.halfUx(), light.halfUy(), light.halfUz());
                put3(target, offset + 8, light.halfVx(), light.halfVy(), light.halfVz());
                double crossX = light.halfUy() * light.halfVz() - light.halfUz() * light.halfVy();
                double crossY = light.halfUz() * light.halfVx() - light.halfUx() * light.halfVz();
                double crossZ = light.halfUx() * light.halfVy() - light.halfUy() * light.halfVx();
                target[offset + 11] = crossX * light.normalX() + crossY * light.normalY()
                        + crossZ * light.normalZ() < 0.0 ? -1.0f : 1.0f;
                put3(target, offset + 12, light.radianceRedCdM2(), light.radianceGreenCdM2(),
                        light.radianceBlueCdM2());
            }
            case LightDescriptor.Point light -> {
                putPosition(target, offset, light, rebaseX, rebaseY, rebaseZ);
                target[offset + 3] = Float.intBitsToFloat(1);
                target[offset + 7] = (float) (light.rangeMeters() / metersPerWorldUnit);
                target[offset + 11] = -1.0f;
                put3(target, offset + 12, light.intensityRedCandela(), light.intensityGreenCandela(),
                        light.intensityBlueCandela());
            }
            case LightDescriptor.Spot light -> {
                putPosition(target, offset, light, rebaseX, rebaseY, rebaseZ);
                target[offset + 3] = Float.intBitsToFloat(2);
                double length = Math.sqrt(light.directionX() * light.directionX()
                        + light.directionY() * light.directionY()
                        + light.directionZ() * light.directionZ());
                put3(target, offset + 4, light.directionX() / length,
                        light.directionY() / length, light.directionZ() / length);
                target[offset + 7] = (float) (light.rangeMeters() / metersPerWorldUnit);
                target[offset + 11] = (float) Math.cos(light.outerHalfAngleRadians());
                put3(target, offset + 12, light.intensityRedCandela(), light.intensityGreenCandela(),
                        light.intensityBlueCandela());
            }
        }
        target[offset + 15] = (float) canonical.luminousPowerLumens();
    }

    /** A distant source has no position and no world scale: direction, lux and a cone are the record. */
    static void encodeDistant(float[] target, int offset, LightDescriptor.Distant light) {
        DistantLight canonical = DistantLight.from(light);
        target[offset + 3] = Float.intBitsToFloat(3);
        put3(target, offset + 4, canonical.directionX(), canonical.directionY(),
                canonical.directionZ());
        target[offset + 11] = (float) Math.cos(light.angularRadiusRadians());
        put3(target, offset + 12, light.illuminanceRedLux(), light.illuminanceGreenLux(),
                light.illuminanceBlueLux());
        target[offset + 15] = luminance(light.illuminanceRedLux(),
                light.illuminanceGreenLux(), light.illuminanceBlueLux());
    }

    private static void encodeNode(float[] target, int offset, LightBvh.Node node,
                                   double rebaseX, double rebaseY, double rebaseZ) {
        LightBvh.Aabb bounds = node.bounds();
        put3(target, offset, bounds.minX() - rebaseX, bounds.minY() - rebaseY,
                bounds.minZ() - rebaseZ);
        target[offset + 3] = Float.intBitsToFloat(node.left());
        put3(target, offset + 4, bounds.maxX() - rebaseX, bounds.maxY() - rebaseY,
                bounds.maxZ() - rebaseZ);
        target[offset + 7] = Float.intBitsToFloat(node.right());
        target[offset + 8] = (float) node.peakLuminousIntensityCandela();
        target[offset + 9] = Float.intBitsToFloat(node.lightIndex());
    }

    private static void putPosition(float[] target, int offset, LightDescriptor.Finite light,
                                    double rebaseX, double rebaseY, double rebaseZ) {
        put3(target, offset, light.positionX() - rebaseX, light.positionY() - rebaseY,
                light.positionZ() - rebaseZ);
    }

    private static void put3(float[] target, int offset, double x, double y, double z) {
        target[offset] = (float) x;
        target[offset + 1] = (float) y;
        target[offset + 2] = (float) z;
    }

    private static float luminance(double red, double green, double blue) {
        return (float) Math.max(0.0, 0.27222872 * red + 0.67408177 * green + 0.05368952 * blue);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Superseded light scene build");
    }

    public record Data(float[] packedLights, float[] packedNodes, LightBvh.Data lightBvh,
                       int lightCount, int rootNodeIndex,
                       double rebaseX, double rebaseY, double rebaseZ,
                       double metersPerWorldUnit) {
        long lightBytes() {
            return Math.multiplyExact((long) packedLights.length, Float.BYTES);
        }

        long nodeBytes() {
            return Math.multiplyExact((long) packedNodes.length, Float.BYTES);
        }
    }
}
