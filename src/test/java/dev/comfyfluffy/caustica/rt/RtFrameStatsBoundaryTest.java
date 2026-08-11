package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtFrameStatsBoundaryTest {
    @Test
    void outputLocationIsAbsoluteLazyAndFixedAfterWriterInitialization(@TempDir Path temporary) {
        Path requested = temporary.resolve("nested").resolve("..").resolve("stats");
        RtFrameStats.OutputLocation output = new RtFrameStats.OutputLocation(Path.of("default-stats"));

        output.configure(requested);

        Path expected = requested.toAbsolutePath().normalize();
        assertEquals(expected, output.directory());
        assertFalse(Files.exists(expected), "configuration must not create the output directory");
        assertEquals(expected, output.beginWriterInitialization());
        assertThrows(IllegalStateException.class, () -> output.configure(temporary.resolve("other")));
    }

    @Test
    void processWorkingDirectoryDefaultIsNormalizedAndAbsolute() {
        Path output = RtFrameStats.defaultOutputDirectory();
        assertTrue(output.isAbsolute());
        assertEquals(output.normalize(), output);
        assertEquals("rt-frame-stats", output.getFileName().toString());
    }

    @Test
    void frameStatsDoesNotImportMinecraftOrFabric() throws IOException {
        Path source = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "RtFrameStats.java").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "frame stats source is missing: " + source);

        List<String> violations = Files.readAllLines(source).stream()
                .filter(line -> line.startsWith("import "))
                .filter(line -> line.contains("net.fabricmc.")
                        || line.contains("net.minecraft.")
                        || line.contains("dev.comfyfluffy.caustica.minecraft."))
                .toList();
        assertTrue(violations.isEmpty(), "frame stats crossed the host import firewall:\n"
                + String.join("\n", violations));
    }

    @Test
    void rendererSchemaOwnsGenericGeometryAndFrameStages() {
        RtFrameStats.MetricSchema schema = RtFrameStats.rendererFrameMetrics();
        assertTrue(schema.stages().stream().anyMatch(stage -> stage.name().equals("geometry.blasRecord")));
        assertTrue(schema.stages().stream().filter(stage -> stage.name().startsWith("frame."))
                .allMatch(RtFrameStats.StageMetric::contributesToAccountedTime));
        assertTrue(schema.counters().isEmpty());
    }

    @Test
    void metricSchemasRejectDuplicatesAndProfilesRejectLateOrRepeatedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RtFrameStats.MetricSchema(List.of(
                new RtFrameStats.StageMetric("duplicate", true),
                new RtFrameStats.StageMetric("duplicate", false)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RtFrameStats.MetricSchema(
                List.of(new RtFrameStats.StageMetric("duplicate", true)), List.of("duplicate")));

        RtFrameStats.MetricSchema base = new RtFrameStats.MetricSchema(
                List.of(new RtFrameStats.StageMetric("base", true)), List.of());
        RtFrameStats.Profile collision = new RtFrameStats.Profile("collision", base, false);
        assertThrows(IllegalArgumentException.class, () -> collision.configureMetrics(
                new RtFrameStats.MetricSchema(List.of(new RtFrameStats.StageMetric("base", true)), List.of())));

        RtFrameStats.Profile repeated = new RtFrameStats.Profile("repeated", base, false);
        repeated.configureMetrics(new RtFrameStats.MetricSchema(List.of(), List.of("host")));
        assertThrows(IllegalStateException.class, () -> repeated.configureMetrics(
                new RtFrameStats.MetricSchema(List.of(), List.of("other"))));

        RtFrameStats.Profile used = new RtFrameStats.Profile("used", base, false);
        used.begin();
        assertThrows(IllegalStateException.class, () -> used.configureMetrics(
                new RtFrameStats.MetricSchema(List.of(), List.of("late"))));
    }

    @Test
    void explicitAccountingExcludesNestedDetailStages() {
        RtFrameStats.MetricSchema schema = new RtFrameStats.MetricSchema(List.of(
                new RtFrameStats.StageMetric("outer", true),
                new RtFrameStats.StageMetric("outer.detail", false),
                new RtFrameStats.StageMetric("next", true)), List.of());

        assertEquals(17L, schema.accountedNanos(new long[]{10L, 6L, 7L}));
    }
}
