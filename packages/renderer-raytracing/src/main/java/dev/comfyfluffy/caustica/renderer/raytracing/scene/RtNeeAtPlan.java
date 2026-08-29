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
        if (!(metersPerSceneUnit > 0.0) || !Double.isFinite(metersPerSceneUnit)) {
            throw new IllegalArgumentException("metersPerSceneUnit must be finite and positive");
        }
        Map<Long, Integer> previousIndices = new HashMap<>();
        for (int index = 0; index < previous.size(); index++) {
            previousIndices.put(previous.get(index).identity(), index);
        }
        Map<Long, Integer> currentIndices = new HashMap<>();
        for (int index = 0; index < current.size(); index++) {
            currentIndices.put(current.get(index).identity(), index);
        }
        int[] currentToPrevious = new int[current.size()];
        float[] power = new float[current.size()];
        for (int index = 0; index < current.size(); index++) {
            var light = current.get(index);
            currentToPrevious[index] = previousIndices.getOrDefault(light.identity(), NO_LIGHT);
            power[index] = samplingPower(light.descriptor(), metersPerSceneUnit);
        }
        int[] previousToCurrent = new int[previous.size()];
        for (int index = 0; index < previous.size(); index++) {
            previousToCurrent[index] = currentIndices.getOrDefault(previous.get(index).identity(), NO_LIGHT);
        }
        return new Plan(currentToPrevious, previousToCurrent, power);
    }

    static float samplingPower(LightDescriptor descriptor, double metersPerSceneUnit) {
        return switch (descriptor) {
            case LightDescriptor.Rectangle light -> {
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

    record Plan(int[] currentToPrevious, int[] previousToCurrent, float[] power) {
        Plan {
            currentToPrevious = currentToPrevious.clone();
            previousToCurrent = previousToCurrent.clone();
            power = power.clone();
        }

        ByteBuffer pack() {
            int count = Math.max(currentToPrevious.length, previousToCurrent.length);
            ByteBuffer result = ByteBuffer.allocate(Math.multiplyExact(count, 3 * Integer.BYTES))
                    .order(ByteOrder.nativeOrder());
            for (int index = 0; index < count; index++) {
                result.putInt(index < currentToPrevious.length ? currentToPrevious[index] : NO_LIGHT);
                result.putInt(index < previousToCurrent.length ? previousToCurrent[index] : NO_LIGHT);
                result.putFloat(index < power.length ? power[index] : 0.0f);
            }
            return result.flip();
        }
    }
}
