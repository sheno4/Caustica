package dev.comfyfluffy.caustica.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Minecraft-owned photometric calibration loaded from the bundled look asset. */
public record MinecraftLightingCalibration(float sunIlluminanceLux, float moonIlluminanceLux,
                                            float blockEmissionLuminanceCdM2,
                                            float nightAirglowLuminanceCdM2,
                                            float starLuminanceCdM2,
                                            float moonPhaseFixedFraction) {
    private static final String RESOURCE = "/caustica/color/looks/default/look.json";
    private static final MinecraftLightingCalibration CURRENT = load();

    public static MinecraftLightingCalibration current() {
        return CURRENT;
    }

    static MinecraftLightingCalibration parse(JsonObject root, String resource) {
        JsonObject lighting = root.getAsJsonObject("lighting");
        if (lighting == null) throw new IllegalArgumentException(resource + ": missing lighting");
        MinecraftLightingCalibration value = new MinecraftLightingCalibration(
                positive(lighting, "sunIlluminanceLux", resource),
                positive(lighting, "moonIlluminanceLux", resource),
                positive(lighting, "blockEmissionLuminanceCdM2", resource),
                nonNegative(lighting, "nightAirglowLuminanceCdM2", resource),
                nonNegative(lighting, "starLuminanceCdM2", resource),
                nonNegative(lighting, "moonPhaseFixedFraction", resource));
        if (value.moonPhaseFixedFraction > 1.0f) {
            throw new IllegalArgumentException(resource + ": moonPhaseFixedFraction must be in [0,1]");
        }
        return value;
    }

    private static MinecraftLightingCalibration load() {
        try (var stream = MinecraftLightingCalibration.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("missing calibration " + RESOURCE);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader).getAsJsonObject(), RESOURCE);
            }
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static float positive(JsonObject object, String name, String resource) {
        float value = finite(object, name, resource);
        if (value <= 0.0f) throw new IllegalArgumentException(resource + ": " + name + " must be positive");
        return value;
    }

    private static float nonNegative(JsonObject object, String name, String resource) {
        float value = finite(object, name, resource);
        if (value < 0.0f) throw new IllegalArgumentException(resource + ": " + name + " must be non-negative");
        return value;
    }

    private static float finite(JsonObject object, String name, String resource) {
        if (!object.has(name)) throw new IllegalArgumentException(resource + ": missing " + name);
        float value = object.get(name).getAsFloat();
        if (!Float.isFinite(value)) throw new IllegalArgumentException(resource + ": " + name + " must be finite");
        return value;
    }
}
