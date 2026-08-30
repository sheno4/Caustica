package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftLightingCalibrationTest {
    private static final String JSON = """
            {"lighting":{"sunIlluminanceLux":128000,"moonIlluminanceLux":5,
            "blockEmissionLuminanceCdM2":2000,"nightAirglowLuminanceCdM2":0.002,
            "starLuminanceCdM2":10,"moonPhaseFixedFraction":0.1}}
            """;

    @Test
    void parsesExistingMinecraftCalibrationValues() {
        var value = parse(JSON);
        assertEquals(128000.0f, value.sunIlluminanceLux());
        assertEquals(2000.0f, value.blockEmissionLuminanceCdM2());
        assertEquals(0.1f, value.moonPhaseFixedFraction());
    }

    @Test
    void rejectsInvalidPhaseFraction() {
        assertThrows(IllegalArgumentException.class, () -> parse(JSON.replace("0.1", "1.5")));
    }

    private static MinecraftLightingCalibration parse(String json) {
        return MinecraftLightingCalibrationLoader.parse(JsonParser.parseString(json).getAsJsonObject(), "test");
    }
}
