package dev.comfyfluffy.caustica.rt.shader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RendererShaderNeutralityTest {
    private static final List<String> REMOVED_IDENTIFIERS = List.of(
            "DIMENSION_ULTRAWARM",
            "environmentTemperature",
            "environmentHumidity",
            "proceduralDomainAnchor",
            "PAYLOAD_SHOW_CELESTIAL",
            "showCelestialBodies",
            "caustica_sky_atmosphere",
            "caustica_sky_bindings",
            "caustica_sky_slot",
            "caustica_celestial_dressing",
            "PAYLOAD_WATER",
            "BINDING_RECORD_CROSSING",
            "waterHitT",
            "INSET_TRANSMIT_BIAS",
            "LutSky");

    @Test
    void rendererOwnedShadersExcludeHostSkyVocabularyAndRemovedAbiNames() throws IOException {
        Path root = Path.of("src", "main", "resources", "caustica", "shaders")
                .toAbsolutePath().normalize();
        List<Path> rendererRoots = List.of(root.resolve("api"), root.resolve("world"),
                root.resolve("builtin"));
        List<String> violations = new ArrayList<>();
        for (Path rendererRoot : rendererRoots) {
            try (var paths = Files.walk(rendererRoot)) {
                for (Path source : paths.filter(path -> path.toString().endsWith(".slang")).toList()) {
                    List<String> lines = Files.readAllLines(source);
                    for (int line = 0; line < lines.size(); line++) {
                        String text = lines.get(line);
                        String lower = text.toLowerCase(Locale.ROOT);
                        if (lower.contains("minecraft") || lower.contains("vanilla")
                                || lower.contains("overworld") || lower.contains("biome")) {
                            violations.add(root.relativize(source) + ":" + (line + 1) + ": " + text);
                            continue;
                        }
                        for (String identifier : REMOVED_IDENTIFIERS) {
                            if (text.contains(identifier)) {
                                violations.add(root.relativize(source) + ":" + (line + 1) + ": " + text);
                                break;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "renderer shader crossed its host-neutral content firewall:\n"
                + String.join("\n", violations));
    }

    @Test
    void worldShadersUseAGenericNamedMediumBoundaryPayloadContract() throws IOException {
        Path world = Path.of("src", "main", "resources", "caustica", "shaders", "world")
                .toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(world)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".slang")).toList()) {
                String text = Files.readString(source);
                if (text.toLowerCase(Locale.ROOT).contains("water")) {
                    violations.add(world.relativize(source).toString());
                }
            }
        }
        String common = Files.readString(world.resolve("world_common.slang"));
        String trace = Files.readString(world.resolve("trace.slang"));
        String shadow = Files.readString(world.resolve("shadow_any_hit.rahit.slang"));
        assertTrue(violations.isEmpty(), "world shaders retain a source-specific medium: " + violations);
        assertTrue(common.contains("PAYLOAD_TRACK_MEDIUM_BOUNDARY = 2u"));
        assertTrue(trace.contains("PAYLOAD_TRACK_MEDIUM_BOUNDARY"));
        assertTrue(shadow.contains("PAYLOAD_TRACK_MEDIUM_BOUNDARY"));
    }

    @Test
    void surfaceDispatchAndDirectLightingRetainTheirGenericMediumState() throws IOException {
        Path world = Path.of("src", "main", "resources", "caustica", "shaders", "world");
        String closestHit = Files.readString(world.resolve("closest_hit.slang"));
        String lighting = Files.readString(world.resolve("lighting.slang"));

        assertTrue(closestHit.contains(
                "payloadSetPacked(payload, materialFlags, sqrt(closure.alphaRoughness)"));
        assertTrue(lighting.contains("hasExtinction || boundaryLighting"));
        assertTrue(lighting.contains("exp(-currentMedium.extinction * shadow.boundaryHitT)"));
        assertTrue(lighting.contains("exp(-currentMedium.extinction * mediumDistance)"));
        assertTrue(lighting.contains("? exp(-currentMedium.extinction * shadow.boundaryHitT)"
                + " : float3(0.0)"));
    }

    @Test
    void coverageContinuationUsesItsProducerNeutralBias() throws IOException {
        Path world = Path.of("src", "main", "resources", "caustica", "shaders", "world");
        String core = Files.readString(world.resolve("world_core.slang"));
        String guides = Files.readString(world.resolve("guides.slang"));

        assertTrue(core.contains("COVERAGE_CONTINUATION_BIAS = 1.0e-4"));
        assertTrue(guides.contains("COVERAGE_CONTINUATION_BIAS"));
    }

    @Test
    void minecraftCausticsBacksolveFromTheActualReceiverWithABoundedStep() throws IOException {
        String source = Files.readString(Path.of("src", "main", "resources", "caustica", "shaders",
                "minecraft", "surface", "caustica_water_surface.slang"));
        assertTrue(source.contains("backsolveCausticSurface"));
        assertTrue(source.contains("receiverPosition = input.boundaryPosition"));
        assertTrue(source.contains("- input.lightDirection * input.boundaryDistance"));
        assertTrue(source.contains("maximumStep / max(length(step), 1.0e-5)"));
        assertTrue(source.contains("bounded firefly variance"));
    }
}
