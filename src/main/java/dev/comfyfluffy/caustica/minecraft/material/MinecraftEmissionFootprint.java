package dev.comfyfluffy.caustica.minecraft.material;

/** Immutable footprint implementation used by the Minecraft emission catalog. */
final class MinecraftEmissionFootprint implements MinecraftMaterialSnapshot.Footprint {
    private static final int SAMPLE_FLOATS = 4;

    private final int resolution;
    private final float[] samples;

    private MinecraftEmissionFootprint(int resolution, float[] samples) {
        this.resolution = resolution;
        this.samples = samples;
    }

    @Override
    public int resolution() {
        return resolution;
    }

    @Override
    public int sampleIndex(float coordinate) {
        int index = (int) (coordinate * resolution);
        return Math.max(0, Math.min(resolution - 1, index));
    }

    @Override
    public float r(int x, int y) {
        return samples[offset(x, y)];
    }

    @Override
    public float g(int x, int y) {
        return samples[offset(x, y) + 1];
    }

    @Override
    public float b(int x, int y) {
        return samples[offset(x, y) + 2];
    }

    @Override
    public float weight(int x, int y) {
        return samples[offset(x, y) + 3];
    }

    private int offset(int x, int y) {
        return (y * resolution + x) * SAMPLE_FLOATS;
    }

    static final class Builder {
        private final int resolution;
        private final int width;
        private final int height;
        private final float[] sums;
        private final int[] counts;

        Builder(int resolution, int width, int height) {
            this.resolution = resolution;
            this.width = width;
            this.height = height;
            sums = new float[Math.multiplyExact(Math.multiplyExact(resolution, resolution), SAMPLE_FLOATS)];
            counts = new int[Math.multiplyExact(resolution, resolution)];
        }

        void add(int x, int y, float r, float g, float b, float weight) {
            int sampleX = Math.min(resolution - 1, x * resolution / width);
            int sampleY = Math.min(resolution - 1, y * resolution / height);
            int sample = sampleY * resolution + sampleX;
            int offset = sample * SAMPLE_FLOATS;
            sums[offset] += r;
            sums[offset + 1] += g;
            sums[offset + 2] += b;
            sums[offset + 3] += weight;
            counts[sample]++;
        }

        MinecraftEmissionFootprint build() {
            float totalWeight = 0.0f;
            float[] averaged = new float[sums.length];
            for (int sample = 0; sample < counts.length; sample++) {
                if (counts[sample] == 0) continue;
                float inverseCount = 1.0f / counts[sample];
                int offset = sample * SAMPLE_FLOATS;
                averaged[offset] = sums[offset] * inverseCount;
                averaged[offset + 1] = sums[offset + 1] * inverseCount;
                averaged[offset + 2] = sums[offset + 2] * inverseCount;
                averaged[offset + 3] = sums[offset + 3] * inverseCount;
                totalWeight += averaged[offset + 3];
            }
            return totalWeight > 0.0f ? new MinecraftEmissionFootprint(resolution, averaged) : null;
        }
    }
}
