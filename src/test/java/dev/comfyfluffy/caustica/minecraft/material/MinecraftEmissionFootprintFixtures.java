package dev.comfyfluffy.caustica.minecraft.material;

/** Test fixtures for immutable emission footprints. */
public final class MinecraftEmissionFootprintFixtures {
    private MinecraftEmissionFootprintFixtures() {
    }

    public static MinecraftEmissionFootprint footprint(int resolution, float[] weights, float[] colors) {
        MinecraftEmissionFootprint.Builder builder =
                new MinecraftEmissionFootprint.Builder(resolution, resolution, resolution);
        for (int y = 0; y < resolution; y++) {
            for (int x = 0; x < resolution; x++) {
                int sample = y * resolution + x;
                float color = colors[sample];
                builder.add(x, y, color, color, color, weights[sample]);
            }
        }
        return builder.build();
    }
}
