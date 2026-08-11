package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class RendererVocabularyFirewallTest {
    private static final Path JAVA = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica")
            .toAbsolutePath().normalize();

    private static final Map<String, List<String>> RETIRED_TERMS = Map.ofEntries(
            Map.entry("rt/pipeline/RtPipeline.java", List.of(
                    "setEntityAlbedoTexture", "entity albedo", "entity-albedo", "entity-texture",
                    "terrain class", "then entity records", "terrain and entity",
                    "triangle spike and terrain")),
            Map.entry("rt/material/RtMaterialAbi.java", List.of(
                    "TERRAIN_PRIM", "terrain material", "terrain index")),
            Map.entry("rt/RtColor.java", List.of("vanilla authors", "Minecraft colour")),
            Map.entry("rt/RtReflex.java", List.of(
                    "VulkanGpuSurfaceMixin", "MinecraftMixin", "runTick", "Minecraft can call",
                    "the game) thread")),
            Map.entry("rt/RtGpuExecutor.java", List.of(
                    "TERRAIN_READ_STAGES", "terrain build timeline", "terrain, TLAS, entity")),
            Map.entry("rt/pipeline/RtSdrPresentPipeline.java", List.of("Minecraft's SDR main target")),
            Map.entry("rt/pipeline/RtHdrCompositePipeline.java", List.of("vanilla UI overlay")),
            Map.entry("rt/RtOpenExrWriter.java", List.of("Minecraft is running")),
            Map.entry("rt/geometry/RtSceneGeometryManager.java", List.of(
                    "host terrain", "until it is migrated")));

    @Test
    void rendererUsesProducerNeutralTermsAtRetiredBoundaries() throws IOException {
        for (Map.Entry<String, List<String>> entry : RETIRED_TERMS.entrySet()) {
            Path source = JAVA.resolve(entry.getKey());
            String content = Files.readString(source);
            for (String retired : entry.getValue()) {
                assertFalse(content.contains(retired), source + " still contains retired term " + retired);
            }
        }
    }
}
