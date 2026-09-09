package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.MetricSchema;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetryImpl;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftFrameMetricsTest {
    @Test
    void preRenderTicksShareUpcomingFrameAndPreserveNestedStagesAndCounters(@TempDir Path temporary) throws Exception {
        var telemetry = new RtTelemetryImpl();
        telemetry.configure(MinecraftFrameMetrics.schema());
        Path output = temporary.resolve("tick-boundary.jfr");
        try (var recording = new Recording()) {
            recording.enable("dev.comfyfluffy.caustica.CpuStage");
            recording.enable("dev.comfyfluffy.caustica.FrameCounter");
            recording.start();
            for (int tick = 0; tick < 2; tick++) {
                try (var ignored = MinecraftFrameMetrics.beginTick(telemetry)) {
                    long terrain = telemetry.frame().startStage();
                    telemetry.frame().count("sectionsSnapshotted", 1);
                    telemetry.frame().endStage("terrain.tick", terrain);
                }
                telemetry.endFrame();
            }
            assertEquals(0, telemetry.frameSerial());
            telemetry.beginRenderFrame();
            telemetry.beginFrameIfInactive();
            telemetry.frame().endStage("frame.capture", telemetry.frame().startStage());
            telemetry.endFrame();
            telemetry.beginRenderFrame();
            telemetry.beginFrameIfInactive();
            telemetry.endFrame();
            recording.stop();
            recording.dump(output);
        }
        var events = RecordingFile.readAllEvents(output);
        var stages = events.stream()
                .filter(event -> event.getEventType().getName().equals("dev.comfyfluffy.caustica.CpuStage"))
                .toList();
        assertEquals(List.of("terrain.tick", "runtime.tick", "terrain.tick", "runtime.tick", "frame.capture"),
                stages.stream().map(event -> event.getString("stage")).toList());
        assertTrue(stages.stream().allMatch(event -> event.getLong("frameId") == 1));
        for (int index = 0; index < 4; index += 2) {
            var terrain = stages.get(index);
            var inclusive = stages.get(index + 1);
            assertTrue(inclusive.getLong("startedNanos") <= terrain.getLong("startedNanos"));
            assertTrue(inclusive.getLong("startedNanos") + inclusive.getLong("elapsedNanos")
                    >= terrain.getLong("startedNanos") + terrain.getLong("elapsedNanos"));
        }
        var counters = events.stream()
                .filter(event -> event.getEventType().getName().equals("dev.comfyfluffy.caustica.FrameCounter"))
                .filter(event -> event.getString("counter").equals("sectionsSnapshotted"))
                .toList();
        assertEquals(List.of(1L, 2L), counters.stream().map(event -> event.getLong("frameId")).toList());
        assertEquals(List.of(2L, 0L), counters.stream().map(event -> event.getLong("value")).toList());
    }

    @Test
    void captureAndPresentationShareTheFrameUntilTextureRetirement(@TempDir Path temporary) throws Exception {
        var telemetry = new RtTelemetryImpl();
        telemetry.configure(MinecraftFrameMetrics.schema());
        Path output = temporary.resolve("host-frame-boundary.jfr");
        try (var recording = new Recording()) {
            recording.enable("dev.comfyfluffy.caustica.Frame");
            recording.enable("dev.comfyfluffy.caustica.CpuStage");
            recording.start();
            try (var ignored = MinecraftFrameMetrics.stage(telemetry, "terrain.markDirty")) {
                telemetry.frame().count("sectionsSnapshotted", 1);
            }
            telemetry.endFrame();
            assertEquals(0L, telemetry.frameSerial());
            try (var ignored = MinecraftFrameMetrics.stage(telemetry, "runtime.frameSetup")) {
                telemetry.beginRenderFrame();
            }
            try (var capture = MinecraftFrameMetrics.stage(telemetry, "host.frameCapture")) {
                try (var entity = MinecraftFrameMetrics.stage(telemetry, "entity.capture")) {
                    telemetry.frame().count("entitiesCaptured", 1);
                }
            }
            try (var ignored = MinecraftFrameMetrics.stage(telemetry, "presentation.hdr")) {
                telemetry.frame().count("sectionsSnapshotted", 1);
            }
            try (var ignored = MinecraftFrameMetrics.stage(telemetry, "presentation.generatedPresent")) { }
            try (var ignored = MinecraftFrameMetrics.stage(telemetry, "host.textureRetire")) { }
            telemetry.endFrame();
            assertEquals(0L, telemetry.frame().startStage());
            assertEquals(2L, telemetry.latestFrame().counters().get("sectionsSnapshotted"));
            try (var ignored = MinecraftFrameMetrics.beginTick(telemetry)) { }
            telemetry.beginRenderFrame();
            telemetry.endFrame();
            recording.stop();
            recording.dump(output);
        }
        var events = RecordingFile.readAllEvents(output);
        var stages = events.stream()
                .filter(event -> event.getEventType().getName().equals("dev.comfyfluffy.caustica.CpuStage"))
                .toList();
        assertEquals(List.of("terrain.markDirty", "runtime.frameSetup", "entity.capture", "host.frameCapture",
                        "presentation.hdr", "presentation.generatedPresent", "host.textureRetire", "runtime.tick"),
                stages.stream().map(event -> event.getString("stage")).toList());
        assertEquals(List.of(1L, 1L, 1L, 1L, 1L, 1L, 1L, 2L),
                stages.stream().map(event -> event.getLong("frameId")).toList());
        var entity = stages.get(2);
        var capture = stages.get(3);
        assertTrue(capture.getLong("startedNanos") <= entity.getLong("startedNanos"));
        assertTrue(capture.getLong("startedNanos") + capture.getLong("elapsedNanos")
                >= entity.getLong("startedNanos") + entity.getLong("elapsedNanos"));
        var frame = events.stream()
                .filter(event -> event.getEventType().getName().equals("dev.comfyfluffy.caustica.Frame"))
                .filter(event -> event.getLong("frameId") == 1L).findFirst().orElseThrow();
        var retirement = stages.get(6);
        assertTrue(frame.getLong("startedNanos") <= stages.getFirst().getLong("startedNanos"));
        assertTrue(frame.getLong("startedNanos") + frame.getLong("elapsedNanos")
                >= retirement.getLong("startedNanos") + retirement.getLong("elapsedNanos"));
    }

    @Test
    void schemaIncludesCurrentTerrainAndEntityMetrics() {
        MetricSchema schema = MinecraftFrameMetrics.schema();
        assertTrue(schema.stages().contains("terrain.lightScenePublish"));
        assertTrue(schema.counters().contains("terrainMaterialEpochRejects"));
        assertTrue(schema.counters().contains("entityPlacementFreshnessEligible"));
        assertTrue(schema.counters().contains("entityPlacementInitialSubmissions"));
        assertTrue(schema.counters().contains("blockEntityGeometrySubmissions"));
        assertTrue(schema.counters().contains("blockEntityGeometryDeferred"));
        assertTrue(schema.counters().contains("entitiesCaptured"));
    }

}
