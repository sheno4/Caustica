package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** CPU-side stable-identity and physical-power inputs for the GPU adaptive sampler. */
final class RtNeeAtPlan {
    static final int NO_LIGHT = 0xffff_ffff;
    static final double DISTANT_REFERENCE_AREA_M2 = 1.0;

    private RtNeeAtPlan() { }

    static Plan build(List<RtRetainedSceneBackend.SceneLight> current,
                      List<RtRetainedSceneBackend.SceneLight> previous,
                      double metersPerSceneUnit) {
        Cache cache = new Cache();
        cache.prepare(previous, false, metersPerSceneUnit);
        return cache.prepare(current, true, metersPerSceneUnit);
    }

    /** Captured light lists are immutable revisions; reference identity permits constant-time reuse. */
    static final class Cache {
        private List<RtRetainedSceneBackend.SceneLight> lights = List.of();
        private double scale = Double.NaN;
        private float[] power = new float[0];
        private int[] identity = new int[0];
        private Plan stable;
        private Plan withoutHistory;

        Plan prepare(List<RtRetainedSceneBackend.SceneLight> current, boolean continuous,
                     double metersPerSceneUnit) {
            if (!(metersPerSceneUnit > 0.0) || !Double.isFinite(metersPerSceneUnit)) {
                throw new IllegalArgumentException("metersPerSceneUnit must be finite and positive");
            }
            if (current == lights && scale == metersPerSceneUnit) {
                return continuous ? stable : withoutHistory;
            }
            boolean sameMembership = current.size() == lights.size();
            for (int index = 0; sameMembership && index < current.size(); index++) {
                sameMembership = current.get(index).identity() == lights.get(index).identity();
            }
            int[] remap;
            if (sameMembership) {
                remap = identity;
            } else {
                Map<Long, Integer> currentIndices = new HashMap<>();
                identity = new int[current.size()];
                for (int index = 0; index < current.size(); index++) {
                    currentIndices.put(current.get(index).identity(), index);
                    identity[index] = index;
                }
                remap = new int[lights.size()];
                for (int index = 0; index < lights.size(); index++) {
                    remap[index] = currentIndices.getOrDefault(lights.get(index).identity(), NO_LIGHT);
                }
            }
            float[] nextPower = new float[current.size()];
            if (scale == metersPerSceneUnit) {
                for (int index = 0; index < lights.size(); index++) {
                    int next = remap[index];
                    if (next != NO_LIGHT && lights.get(index).descriptor() == current.get(next).descriptor()) {
                        nextPower[next] = power[index];
                    }
                }
            }
            for (int index = 0; index < current.size(); index++) {
                if (nextPower[index] == 0.0f) {
                    nextPower[index] = samplingPower(current.get(index).descriptor(), metersPerSceneUnit);
                }
            }
            power = nextPower;
            scale = metersPerSceneUnit;
            lights = current;
            float total = powerTotal(power);
            stable = new Plan(identity, power, total);
            withoutHistory = new Plan(new int[0], power, total);
            return !continuous ? withoutHistory : sameMembership ? stable : new Plan(remap, power, total);
        }
    }

    /**
     * Sum of every light's sampling power, accumulated in double precision so the GPU can normalize
     * the power-based prior with a single divide instead of a reduction over the light table.
     */
    static float powerTotal(float[] power) {
        double total = 0.0;
        for (float value : power) total += value;
        return (float) total;
    }

    static float samplingPower(LightDescriptor descriptor, double metersPerSceneUnit) {
        return switch (descriptor) {
            case LightDescriptor.Parallelogram light -> {
                double cx = light.halfUy() * light.halfVz() - light.halfUz() * light.halfVy();
                double cy = light.halfUz() * light.halfVx() - light.halfUx() * light.halfVz();
                double cz = light.halfUx() * light.halfVy() - light.halfUy() * light.halfVx();
                double areaSceneUnits = 4.0 * Math.sqrt(cx * cx + cy * cy + cz * cz);
                double areaM2 = areaSceneUnits * metersPerSceneUnit * metersPerSceneUnit;
                yield positive(Math.PI * areaM2 * luminance(light.radianceRedCdM2(),
                        light.radianceGreenCdM2(), light.radianceBlueCdM2()));
            }
            case LightDescriptor.Spot light -> {
                double solidAngle = 2.0 * Math.PI * (1.0 - Math.cos(light.halfAngleRadians()));
                yield positive(solidAngle * luminance(light.intensityRedCandela(),
                        light.intensityGreenCandela(), light.intensityBlueCandela()));
            }
            case LightDescriptor.Distant light -> positive(DISTANT_REFERENCE_AREA_M2 * luminance(
                    light.illuminanceRedLux(), light.illuminanceGreenLux(),
                    light.illuminanceBlueLux()));
        };
    }

    private static double luminance(double red, double green, double blue) {
        return 0.2722287168 * red + 0.6740817658 * green + 0.0536895174 * blue;
    }

    private static float positive(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("light sampling power exceeds GPU range");
        return (float) Math.clamp(value, 1.0e-8, 1.0e30);
    }

    /** Arrays are private plan storage shared only with the revision cache and never mutated. */
    static final class Plan {
        private final int[] previousToCurrent;
        private final float[] power;
        private final float powerTotal;
        private ByteBuffer packed;

        Plan(int[] previousToCurrent, float[] power, float powerTotal) {
            this.previousToCurrent = previousToCurrent;
            this.power = power;
            this.powerTotal = powerTotal;
        }

        int[] previousToCurrent() { return previousToCurrent; }
        float[] power() { return power; }
        float powerTotal() { return powerTotal; }

        ByteBuffer pack() {
            if (packed != null) return packed;
            int count = Math.max(previousToCurrent.length, power.length);
            ByteBuffer result = ByteBuffer.allocate(Math.multiplyExact(count, 2 * Integer.BYTES))
                    .order(ByteOrder.nativeOrder());
            for (int index = 0; index < count; index++) {
                result.putInt(index < previousToCurrent.length ? previousToCurrent[index] : NO_LIGHT);
                result.putFloat(index < power.length ? power[index] : 0.0f);
            }
            packed = result.flip().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            return packed;
        }
    }
}
