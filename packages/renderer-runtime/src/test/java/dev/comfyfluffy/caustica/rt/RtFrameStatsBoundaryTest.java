package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.RtTelemetry.MetricSchema;
import dev.comfyfluffy.caustica.rt.RtTelemetry.StageMetric;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void rendererSchemaOwnsGenericGeometryAndFrameStages() {
        MetricSchema schema = RtFrameStats.rendererFrameMetrics();
        assertTrue(schema.stages().stream().anyMatch(stage -> stage.name().equals("geometry.packMaterial")));
        assertTrue(schema.stages().stream().anyMatch(stage -> stage.name().equals("geometry.schedulerValidate")));
        assertTrue(schema.stages().stream().filter(stage -> stage.name().startsWith("frame."))
                .allMatch(StageMetric::contributesToAccountedTime));
        assertTrue(schema.counters().contains("geometryTrianglesSubmitted"));
        assertTrue(schema.counters().contains("geometryInstancesVisible"));
        assertTrue(schema.counters().contains("geometryPlacementFreshnessApplied"));
        assertTrue(schema.counters().contains("geometryGroupsAccepted"));
        assertTrue(schema.counters().contains("geometryPutsAccepted"));
        assertTrue(schema.counters().contains("geometryGroupRevisionsCoalesced"));
        assertTrue(schema.counters().contains("geometryPutRevisionsCoalesced"));
        assertTrue(schema.counters().contains("geometryPutsStarted"));
    }

    @Test
    void metricSchemasRejectDuplicatesAndProfilesRejectLateOrRepeatedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new MetricSchema(List.of(
                new StageMetric("duplicate", true),
                new StageMetric("duplicate", false)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MetricSchema(
                List.of(new StageMetric("duplicate", true)), List.of("duplicate")));

        MetricSchema base = new MetricSchema(
                List.of(new StageMetric("base", true)), List.of());
        RtFrameStats.Profile collision = new RtFrameStats.Profile("collision", base, false);
        assertThrows(IllegalArgumentException.class, () -> collision.configureMetrics(
                new MetricSchema(List.of(new StageMetric("base", true)), List.of())));

        RtFrameStats.Profile repeated = new RtFrameStats.Profile("repeated", base, false);
        repeated.configureMetrics(new MetricSchema(List.of(), List.of("host")));
        assertThrows(IllegalStateException.class, () -> repeated.configureMetrics(
                new MetricSchema(List.of(), List.of("other"))));

        RtFrameStats.Profile used = new RtFrameStats.Profile("used", base, false);
        used.begin();
        assertThrows(IllegalStateException.class, () -> used.configureMetrics(
                new MetricSchema(List.of(), List.of("late"))));
    }

    @Test
    void explicitAccountingExcludesNestedDetailStages() {
        MetricSchema schema = new MetricSchema(List.of(
                new StageMetric("outer", true),
                new StageMetric("outer.detail", false),
                new StageMetric("next", true)), List.of());

        assertEquals(17L, schema.accountedNanos(new long[]{10L, 6L, 7L}));
    }

    @Test
    void counterSetAndMaxRetainTheExpectedFrameValue() {
        boolean previous = CausticaConfig.Rt.FrameStats.ENABLED.value();
        CausticaConfig.Rt.FrameStats.ENABLED.set(true);
        try {
            RtFrameStats.Profile profile = new RtFrameStats.Profile("counter-semantics",
                    new MetricSchema(List.of(), List.of("depth")), false);
            profile.begin();
            profile.set("depth", 3);
            profile.max("depth", 7);
            profile.max("depth", 5);
            assertEquals(7L, profile.counterValue("depth"));
            profile.set("depth", 2);
            assertEquals(2L, profile.counterValue("depth"));
        } finally {
            CausticaConfig.Rt.FrameStats.ENABLED.set(previous);
        }
    }

    @Test
    void renderFrameSerialAdvancesOnlyAtTheExplicitBoundaryAndIsInstanceOwned() {
        RtTelemetryImpl telemetry = new RtTelemetryImpl();
        RtTelemetryImpl other = new RtTelemetryImpl();
        long before = telemetry.frameSerial();
        RtFrameStats.Profile profile = new RtFrameStats.Profile("serial-boundary",
                new MetricSchema(List.of(), List.of()), false);
        profile.begin();
        assertEquals(before, telemetry.frameSerial());
        telemetry.beginRenderFrame();
        assertEquals(before + 1L, telemetry.frameSerial());
        assertEquals(0L, other.frameSerial());
    }
}
