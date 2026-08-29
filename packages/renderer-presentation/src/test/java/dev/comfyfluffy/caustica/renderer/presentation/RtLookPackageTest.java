package dev.comfyfluffy.caustica.renderer.presentation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtLookPackageTest {
    private static final String VALID = """
            {"schemaVersion":5,"id":"test","packageVersion":1,
             "exposure":{"minEv":-15,"maxEv":-2,"curve":"-2:-3,2:-2,8:0,15:1"},
             "lmt":{"resource":"lmt.bin"}}
            """;

    @Test
    void acceptsACompleteCurrentSchemaPackage() {
        RtLookPackage look = parse(VALID);
        assertEquals(-15.0f, look.exposure().minEv());
        assertEquals("/caustica/color/looks/test/lmt.bin", look.lmtResource());
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
    }

    private static RtLookPackage parse(String json) {
        return RtLookPackage.parse(JsonParser.parseString(json).getAsJsonObject(),
                "/caustica/color/looks/test/look.json");
    }
}
