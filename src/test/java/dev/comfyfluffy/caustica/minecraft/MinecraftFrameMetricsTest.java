package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.rt.RtFrameStats.MetricSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftFrameMetricsTest {
    private static final Path MINECRAFT = Path.of("src", "main", "java", "dev", "comfyfluffy",
            "caustica", "minecraft").toAbsolutePath().normalize();

    @Test
    void schemaIncludesNewTerrainAndGeometryTableMetrics() {
        MetricSchema schema = MinecraftFrameMetrics.schema();
        assertTrue(schema.stages().stream().anyMatch(stage -> stage.name().equals("terrain.lightScenePublish")));
        assertTrue(schema.counters().contains("geometryTableFlushes"));
        assertFalse(schema.counters().contains("entityTableFlushes"));
    }

    @Test
    void nestedEntityCaptureDetailsDoNotContributeToAccountedTime() {
        MetricSchema schema = MinecraftFrameMetrics.schema();
        assertTrue(schema.stages().stream()
                .filter(stage -> stage.name().equals("entity.capture"))
                .allMatch(stage -> stage.contributesToAccountedTime()));
        assertTrue(schema.stages().stream()
                .filter(stage -> stage.name().startsWith("entity.capture."))
                .noneMatch(stage -> stage.contributesToAccountedTime()));
    }

    @Test
    void bootstrapConfiguresHostMetricsBesideTheOutputDirectory() throws IOException {
        String bootstrap = Files.readString(MINECRAFT.resolve("MinecraftApiBootstrap.java"));
        int output = bootstrap.indexOf("RtFrameStats.configureOutputDirectory(");
        int metrics = bootstrap.indexOf("RtFrameStats.configureFrameMetrics(MinecraftFrameMetrics.schema());");

        assertTrue(output >= 0);
        assertTrue(metrics > output);
    }

    @Test
    void schemaExactlyCoversLiteralTerrainAndEntityMetricCalls() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String producer : List.of("entity", "terrain")) {
            try (var paths = Files.walk(MINECRAFT.resolve(producer))) {
                sources.addAll(paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList());
            }
        }

        Set<String> stageCalls = new HashSet<>();
        Set<String> counterCalls = new HashSet<>();
        for (Path source : sources) {
            String java = Files.readString(source);
            stageCalls.addAll(literalArguments(java, "RtFrameStats.FRAME.stage"));
            stageCalls.addAll(literalArguments(java, "RtFrameStats.FRAME.endStage"));
            counterCalls.addAll(literalArguments(java, "RtFrameStats.FRAME.count"));
            // The wait helper forwards its literal call-site name to FRAME.count.
            counterCalls.addAll(literalArguments(java, "awaitGraphicsUse"));
        }

        MetricSchema schema = MinecraftFrameMetrics.schema();
        assertEquals(schema.stages().stream().map(stage -> stage.name()).collect(java.util.stream.Collectors.toSet()),
                stageCalls);
        assertEquals(Set.copyOf(schema.counters()), counterCalls);
    }

    private static Set<String> literalArguments(String source, String invocation) {
        Set<String> result = new HashSet<>();
        int searchFrom = 0;
        while (true) {
            int call = source.indexOf(invocation, searchFrom);
            if (call < 0) {
                return result;
            }
            int open = source.indexOf('(', call + invocation.length());
            if (open < 0) {
                return result;
            }
            int depth = 1;
            boolean quoted = false;
            boolean escaped = false;
            StringBuilder literal = null;
            for (int cursor = open + 1; cursor < source.length() && depth > 0; cursor++) {
                char c = source.charAt(cursor);
                if (quoted) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        quoted = false;
                        result.add(literal.toString());
                    } else {
                        literal.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                    literal = new StringBuilder();
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                if (depth == 0) {
                    searchFrom = cursor + 1;
                }
            }
            if (depth != 0) {
                return result;
            }
        }
    }
}
