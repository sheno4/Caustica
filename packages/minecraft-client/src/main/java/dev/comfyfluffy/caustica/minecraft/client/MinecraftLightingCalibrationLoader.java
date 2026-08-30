package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Loads Minecraft photometric calibration at runtime composition. */
final class MinecraftLightingCalibrationLoader {
    private static final String DEFAULT_RESOURCE = "/caustica/color/looks/default/look.json";

    private MinecraftLightingCalibrationLoader() { }

    static MinecraftLightingCalibration loadDefault() {
        try (var stream = MinecraftLightingCalibrationLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (stream == null) throw new IllegalStateException("missing calibration " + DEFAULT_RESOURCE);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader).getAsJsonObject(), DEFAULT_RESOURCE);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("could not load Minecraft lighting calibration", failure);
        }
    }

    static MinecraftLightingCalibration parse(JsonObject root, String resource) {
        JsonObject lighting = root.getAsJsonObject("lighting");
        if (lighting == null) throw new IllegalArgumentException(resource + ": missing lighting");
        try {
            return new MinecraftLightingCalibration(value(lighting, "sunIlluminanceLux"),
                    value(lighting, "moonIlluminanceLux"), value(lighting, "blockEmissionLuminanceCdM2"),
                    value(lighting, "nightAirglowLuminanceCdM2"), value(lighting, "starLuminanceCdM2"),
                    value(lighting, "moonPhaseFixedFraction"));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(resource + ": " + failure.getMessage(), failure);
        }
    }

    private static float value(JsonObject object, String name) {
        if (!object.has(name)) throw new IllegalArgumentException("missing " + name);
        return object.get(name).getAsFloat();
    }
}
