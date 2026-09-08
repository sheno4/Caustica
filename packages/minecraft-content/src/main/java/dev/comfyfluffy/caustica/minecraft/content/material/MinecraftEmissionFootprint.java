package dev.comfyfluffy.caustica.minecraft.content.material;

/** Immutable, downsampled emission footprint used by Minecraft terrain light extraction. */
public final class MinecraftEmissionFootprint {
    private static final int SAMPLE_FLOATS = 4;

    private final int resolution;
    private final float[] samples;

    private MinecraftEmissionFootprint(int resolution, float[] samples) {
        this.resolution = resolution;
        this.samples = samples;
    }

    public int resolution() {
        return resolution;
    }

    public int sampleIndex(float coordinate) {
        int index = (int) (coordinate * resolution);
        return Math.max(0, Math.min(resolution - 1, index));
    }

    public float r(int x, int y) {
        return samples[offset(x, y)];
    }

    public float g(int x, int y) {
        return samples[offset(x, y) + 1];
    }

    public float b(int x, int y) {
        return samples[offset(x, y) + 2];
    }

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
        private final float[] areas;

        Builder(int resolution, int width, int height) {
            this.resolution = resolution;
            this.width = width;
            this.height = height;
            sums = new float[Math.multiplyExact(Math.multiplyExact(resolution, resolution), SAMPLE_FLOATS)];
            areas = new float[Math.multiplyExact(resolution, resolution)];
        }

        void add(int x, int y, float r, float g, float b, float weight) {
            double left = (double) x * resolution / width;
            double right = (double) (x + 1) * resolution / width;
            double top = (double) y * resolution / height;
            double bottom = (double) (y + 1) * resolution / height;
            // A source texel may cover several footprint cells or only part of one.
            for (int sy = (int) top; sy < Math.ceil(bottom); sy++) {
                double overlapY = Math.min(bottom, sy + 1) - Math.max(top, sy);
                for (int sx = (int) left; sx < Math.ceil(right); sx++) {
                    float area = (float) (overlapY * (Math.min(right, sx + 1) - Math.max(left, sx)));
                    int sample = sy * resolution + sx;
                    int offset = sample * SAMPLE_FLOATS;
                    sums[offset] += r * area;
                    sums[offset + 1] += g * area;
                    sums[offset + 2] += b * area;
                    sums[offset + 3] += weight * area;
                    areas[sample] += area;
                }
            }
        }

        MinecraftEmissionFootprint build() {
            float totalWeight = 0.0f;
            float[] averaged = new float[sums.length];
            for (int sample = 0; sample < areas.length; sample++) {
                if (areas[sample] == 0) continue;
                float inverseArea = 1.0f / areas[sample];
                int offset = sample * SAMPLE_FLOATS;
                averaged[offset] = sums[offset] * inverseArea;
                averaged[offset + 1] = sums[offset + 1] * inverseArea;
                averaged[offset + 2] = sums[offset + 2] * inverseArea;
                averaged[offset + 3] = sums[offset + 3] * inverseArea;
                totalWeight += averaged[offset + 3];
            }
            return totalWeight > 0.0f ? new MinecraftEmissionFootprint(resolution, averaged) : null;
        }
    }
}
