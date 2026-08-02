package dev.comfyfluffy.caustica.rt.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RayPackManifestTest {
    private static final String DEFAULT_MANIFEST = "/caustica/raypacks/default/pack.json";

    @Test
    void defaultPackDeclaresTheCurrentContractWithoutRuntimeRegistration() throws Exception {
        try (var input = RayPackManifestTest.class.getResourceAsStream(DEFAULT_MANIFEST)) {
            if (input == null) {
                throw new IllegalStateException("missing " + DEFAULT_MANIFEST);
            }
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                assertFalse(json.has("requiredEngineServices"));
                assertFalse(json.has("services"));
                assertFalse(json.has("passes"));
                assertFalse(json.has("compute"));
                RayPackManifest manifest = RayPackManifest.parse(json, DEFAULT_MANIFEST);
                assertEquals(new RayPackId("caustica", "default"), manifest.id());
                assertEquals("default_pack", manifest.slang().module());
                assertEquals("DefaultRayPack", manifest.slang().type());
                assertEquals(RayPackContract.API_VERSION, manifest.api());
                assertEquals(new RayPackId("caustica", "default"), manifest.lookPackage());
            }
        }
    }

    @Test
    void preOneAPICompatibilityRequiresAnExactVersion() {
        assertFalse(RayPackContract.supports(new RayPackContract.ApiVersion(0, 0)));
        assertTrue(RayPackContract.supports(new RayPackContract.ApiVersion(0, 1)));
        assertFalse(RayPackContract.supports(new RayPackContract.ApiVersion(0, 2)));
        assertFalse(RayPackContract.supports(new RayPackContract.ApiVersion(1, 0)));
    }

    @Test
    void schemaRejectsUnknownAndMalformedFields() {
        var unknownRoot = JsonParser.parseString("""
                {
                  "format": 1,
                  "id": "caustica:test",
                  "version": "0.1.0",
                  "api": {"major": 0, "minor": 1},
                  "slang": {"module": "test", "type": "TestPack", "sourceRoot": "shaders"},
                  "lookPackage": "caustica:test",
                  "compute": []
                }
                """).getAsJsonObject();
        var malformedApi = JsonParser.parseString("""
                {
                  "format": 1,
                  "id": "caustica:test",
                  "version": "0.1.0",
                  "api": {"major": 0, "minor": "1"},
                  "slang": {"module": "test", "type": "TestPack", "sourceRoot": "shaders"},
                  "lookPackage": "caustica:test"
                }
                """).getAsJsonObject();
        var unknownNested = JsonParser.parseString("""
                {
                  "format": 1,
                  "id": "caustica:test",
                  "version": "0.1.0",
                  "api": {"major": 0, "minor": 1},
                  "slang": {
                    "module": "test",
                    "type": "TestPack",
                    "sourceRoot": "shaders",
                    "entryPoint": "main"
                  },
                  "lookPackage": "caustica:test"
                }
                """).getAsJsonObject();

        var rootError = assertThrows(IllegalArgumentException.class,
                () -> RayPackManifest.parse(unknownRoot, "unknown-root.json"));
        var apiError = assertThrows(IllegalArgumentException.class,
                () -> RayPackManifest.parse(malformedApi, "malformed-api.json"));
        var nestedError = assertThrows(IllegalArgumentException.class,
                () -> RayPackManifest.parse(unknownNested, "unknown-nested.json"));

        assertTrue(rootError.getMessage().contains("compute"));
        assertTrue(apiError.getMessage().contains("$/api/minor"));
        assertTrue(nestedError.getMessage().contains("entryPoint"));
    }

    @Test
    void rejectsExecutableAndEscapingSourceShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> new RayPackManifest.SlangImplementation("default_pack.exe",
                        "DefaultRayPack", "shaders"));
        assertThrows(IllegalArgumentException.class,
                () -> new RayPackManifest.SlangImplementation("default_pack",
                        "DefaultRayPack", "../shaders"));
        assertThrows(IllegalArgumentException.class, () -> RayPackId.parse("Default"));
    }
}
