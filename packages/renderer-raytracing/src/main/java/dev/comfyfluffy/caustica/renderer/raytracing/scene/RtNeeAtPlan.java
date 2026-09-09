package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;

/** Immutable identity and physical-power metadata independent of submitted view history. */
final class RtNeeAtPlan {
    static final double DISTANT_REFERENCE_AREA_M2 = 1.0;
    static final int LOOKUP_ENTRY_BYTES = 16;
    private RtNeeAtPlan() { }

    static int hash(long identity) {
        int value = (int) identity ^ (int) (identity >>> 32);
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }

    /** Unchanged immutable source pages reuse their identity and physical-power arrays. */
    static final class Cache {
        private IdentityHashMap<List<RtRetainedSceneBackend.SceneLight>, Page> pages = new IdentityHashMap<>();
        private List<List<RtRetainedSceneBackend.SceneLight>> sourcePages = List.of();
        private double scale = Double.NaN;
        private Plan prepared;

        Plan prepare(List<RtRetainedSceneBackend.SceneLight> current, double metersPerSceneUnit) {
            if (!(metersPerSceneUnit > 0.0) || !Double.isFinite(metersPerSceneUnit)) {
                throw new IllegalArgumentException("metersPerSceneUnit must be finite and positive");
            }
            List<List<RtRetainedSceneBackend.SceneLight>> inputs = SnapshotList.pagesOf(current);
            boolean sameScale = scale == metersPerSceneUnit;
            boolean unchanged = sameScale && prepared != null && inputs.size() == sourcePages.size();
            for (int index = 0; unchanged && index < inputs.size(); index++) {
                unchanged = inputs.get(index) == sourcePages.get(index);
            }
            if (unchanged) return prepared;
            var next = new IdentityHashMap<List<RtRetainedSceneBackend.SceneLight>, Page>();
            long[] identities = new long[current.size()];
            float[] powers = new float[current.size()];
            int first = 0;
            for (var input : inputs) {
                Page page = sameScale ? pages.get(input) : null;
                if (page == null) {
                    long[] pageIdentities = new long[input.size()];
                    float[] pagePowers = new float[input.size()];
                    for (int index = 0; index < input.size(); index++) {
                        var light = input.get(index);
                        pageIdentities[index] = light.identity();
                        pagePowers[index] = samplingPower(light.descriptor(), metersPerSceneUnit);
                    }
                    page = new Page(pageIdentities, pagePowers);
                }
                next.put(input, page);
                System.arraycopy(page.identities, 0, identities, first, page.identities.length);
                System.arraycopy(page.powers, 0, powers, first, page.powers.length);
                first += page.identities.length;
            }
            Plan nextPlan = new Plan(identities, powers);
            pages = next;
            sourcePages = inputs;
            scale = metersPerSceneUnit;
            return prepared = nextPlan;
        }

        private record Page(long[] identities, float[] powers) { }
    }

    /** Accumulating in double precision preserves small lights' contribution to the sampling prior. */
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
                    light.illuminanceRedLux(), light.illuminanceGreenLux(), light.illuminanceBlueLux()));
        };
    }

    private static double luminance(double red, double green, double blue) {
        return 0.2722287168 * red + 0.6740817658 * green + 0.0536895174 * blue;
    }

    private static float positive(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("light sampling power exceeds GPU range");
        return (float) Math.clamp(value, 1.0e-8, 1.0e30);
    }

    static final class Plan {
        private final long[] identities;
        private final float[] power;
        private final long[] lookupIdentities;
        private final int[] lookupIndices;
        private final float powerTotal;

        Plan(long[] identities, float[] power) {
            this.identities = identities;
            this.power = power;
            this.powerTotal = RtNeeAtPlan.powerTotal(power);
            int capacity = 1;
            while (capacity < Math.multiplyExact(identities.length, 2)) capacity = Math.multiplyExact(capacity, 2);
            lookupIdentities = new long[capacity];
            lookupIndices = new int[capacity];
            for (int index = 0; index < identities.length; index++) {
                long identity = identities[index];
                if (identity == 0) throw new IllegalArgumentException("light identity must be nonzero");
                int slot = hash(identity) & mask();
                while (lookupIdentities[slot] != 0) slot = (slot + 1) & mask();
                lookupIdentities[slot] = identity;
                lookupIndices[slot] = index;
            }
        }

        int count() { return identities.length; }
        int mask() { return lookupIdentities.length - 1; }
        float powerTotal() { return powerTotal; }

        ByteBuffer packPower() {
            ByteBuffer bytes = ByteBuffer.allocate(power.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : power) bytes.putFloat(value);
            return bytes.flip();
        }

        ByteBuffer packIdentities() {
            ByteBuffer bytes = ByteBuffer.allocate(identities.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (long identity : identities) bytes.putLong(identity);
            return bytes.flip();
        }

        ByteBuffer packLookup() {
            ByteBuffer bytes = ByteBuffer.allocate(lookupIdentities.length * LOOKUP_ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int slot = 0; slot < lookupIdentities.length; slot++) {
                bytes.putLong(lookupIdentities[slot]).putInt(lookupIndices[slot]).putInt(0);
            }
            return bytes.flip();
        }
    }
}
