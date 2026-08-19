package dev.comfyfluffy.caustica.api.provider;

/**
 * Immutable square sampling footprint for one material's premultiplied linear emission color and coverage.
 * Samples are stored as {@code {r, g, b, weight}} in row-major order.
 */
public final class EmissionFootprint {
    private static final int SAMPLE_FLOATS = 4;

    private final int resolution;
    private final float[] samples;

    public EmissionFootprint(int resolution, float[] samples) {
        if (resolution <= 0) throw new IllegalArgumentException("resolution must be positive");
        int expected = Math.multiplyExact(Math.multiplyExact(resolution, resolution), SAMPLE_FLOATS);
        if (samples.length != expected) {
            throw new IllegalArgumentException("expected " + expected + " footprint sample components, got "
                    + samples.length);
        }
        this.resolution = resolution;
        this.samples = samples.clone();
    }

    public int resolution() {
        return resolution;
    }

    public int sampleCount() {
        return resolution * resolution;
    }

    /** Sample index for a normalized coordinate; values outside {@code [0,1)} clamp to the footprint. */
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

    /** Accumulates premultiplied linear emission color and coverage into a fixed-resolution footprint. */
    public static final class Builder {
        private final int resolution;
        private final float[] sums;
        private final int[] counts;
        private final int width;
        private final int height;

        public Builder(int resolution, int width, int height) {
            if (resolution <= 0) throw new IllegalArgumentException("resolution must be positive");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("source dimensions must be positive");
            }
            this.resolution = resolution;
            this.width = width;
            this.height = height;
            this.sums = new float[Math.multiplyExact(Math.multiplyExact(resolution, resolution), SAMPLE_FLOATS)];
            this.counts = new int[Math.multiplyExact(resolution, resolution)];
        }

        public void add(int x, int y, float r, float g, float b, float weight) {
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

        /** Returns null when the source carries no coverage weight. */
        public EmissionFootprint build() {
            float totalWeight = 0.0f;
            float[] averaged = new float[sums.length];
            for (int sample = 0; sample < counts.length; sample++) {
                int count = counts[sample];
                if (count == 0) continue;
                float inverseCount = 1.0f / count;
                int offset = sample * SAMPLE_FLOATS;
                averaged[offset] = sums[offset] * inverseCount;
                averaged[offset + 1] = sums[offset + 1] * inverseCount;
                averaged[offset + 2] = sums[offset + 2] * inverseCount;
                averaged[offset + 3] = sums[offset + 3] * inverseCount;
                totalWeight += averaged[offset + 3];
            }
            return totalWeight > 0.0f ? new EmissionFootprint(resolution, averaged) : null;
        }
    }
}
