package dev.comfyfluffy.caustica.rt;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtLookPackageTest {
    private static final String VALID = """
            {"schemaVersion":5,"id":"test","packageVersion":1,
             "exposure":{"minEv":-15,"maxEv":-2,"curve":"-2:-3,2:-2,8:0,15:1"},
             "lmt":{"resource":"lmt.bin"},
             "lighting":{"sunIlluminanceLux":128000,"moonIlluminanceLux":5,
             "blockEmissionLuminanceCdM2":2000,"nightAirglowLuminanceCdM2":0.002,
             "starLuminanceCdM2":10,"moonPhaseFixedFraction":0.1}}
            """;

    @Test
    void acceptsACompleteCurrentSchemaPackage() {
        RtLookPackage look = parse(VALID);
        assertEquals(128000.0f, look.lighting().sunIlluminanceLux());
        assertEquals(0.1f, look.lighting().moonPhaseFixedFraction());
    }

    @Test
    void rejectsUnknownSchema() {
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("\"schemaVersion\":5",
                "\"schemaVersion\":4")));
    }

    @Test
    void rejectsInvalidPhysicalRanges() {
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("\"minEv\":-15",
                "\"minEv\":2")));
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace(
                "\"moonPhaseFixedFraction\":0.1", "\"moonPhaseFixedFraction\":1.5")));
    }

    @Test
    void rejectsAMissingLightingSection() {
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("\"lighting\":", "\"nope\":")));
    }

    private static RtLookPackage parse(String json) {
        return RtLookPackage.parse(JsonParser.parseString(json).getAsJsonObject(),
                "/caustica/color/looks/test/look.json");
    }
}
