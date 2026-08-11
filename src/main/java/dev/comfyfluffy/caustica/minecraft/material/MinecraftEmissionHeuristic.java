package dev.comfyfluffy.caustica.minecraft.material;

/** Minecraft's state-gated, albedo-derived emission weight. */
final class MinecraftEmissionHeuristic {
    private static final float DARK_FLOOR = 0.1f;
    private static final float LUMINANCE_POWER = 3.0f;

    private MinecraftEmissionHeuristic() {
    }

    /**
     * RGB is linear and alpha is coverage. Eligibility remains external: callers invoke this only for a
     * texture associated with emitting Minecraft geometry.
     */
    static float weight(float linearR, float linearG, float linearB, float alpha) {
        float value = clamp01(luminance(linearR, linearG, linearB));
        if (value < DARK_FLOOR) {
            return 0.0f;
        }
        return clamp01((float) Math.pow(value, LUMINANCE_POWER) * alpha);
    }

    private static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
