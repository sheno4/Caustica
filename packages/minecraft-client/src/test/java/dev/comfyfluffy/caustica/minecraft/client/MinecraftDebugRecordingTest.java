package dev.comfyfluffy.caustica.minecraft.client;

import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import com.google.gson.JsonParser;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftDebugRecordingTest {
    static final class Marker extends Event { int value = 42; }

    @Test
    void defaultRecordingIncludesRawHostLoopAndWorkEvents() throws Exception {
        try (var recording = MinecraftDebugService.startRecording(new com.google.gson.JsonObject())) {
            assertEquals("true", recording.getSettings().get("dev.comfyfluffy.caustica.HostLoop#enabled"));
            assertEquals("true", recording.getSettings().get("dev.comfyfluffy.caustica.HostWork#enabled"));
            assertEquals("true", recording.getSettings().get("dev.comfyfluffy.caustica.HostCallbackTotals#enabled"));
            assertEquals("true", recording.getSettings().get("dev.comfyfluffy.caustica.HostSubmission#enabled"));
        }
    }

    @Test
    void successfulStartReturnsARunningConfiguredRecording() throws Exception {
        var request = JsonParser.parseString("{\"events\":[\"Frame\"]}").getAsJsonObject();
        try (var recording = MinecraftDebugService.startRecording(request)) {
            assertEquals(RecordingState.RUNNING, recording.getState());
            assertEquals("Caustica debug", recording.getName());
            assertEquals("true", recording.getSettings().get("dev.comfyfluffy.caustica.Frame#enabled"));
        }
    }

    @Test
    void malformedEventRequestDoesNotAllocateARecording() {
        var recorder = FlightRecorder.getFlightRecorder();
        var before = recorder.getRecordings();
        try {
            var request = JsonParser.parseString("{\"events\":[{}]}").getAsJsonObject();
            assertThrows(UnsupportedOperationException.class, () -> MinecraftDebugService.startRecording(request));
            assertEquals(before, recorder.getRecordings());
        } finally {
            recorder.getRecordings().stream().filter(recording -> !before.contains(recording))
                    .forEach(Recording::close);
        }
    }

    @Test
    void stoppingSavesReadableEventsAndClosesRecording(@TempDir Path directory) throws Exception {
        try (var recording = new Recording()) {
            recording.enable(Marker.class);
            recording.start();
            new Marker().commit();
            Path output = directory.resolve("recording.jfr");
            MinecraftDebugService.stopRecording(recording, output);
            assertEquals(RecordingState.CLOSED, recording.getState());
            var events = RecordingFile.readAllEvents(output);
            assertEquals(1, events.size());
            assertEquals(42, events.getFirst().getInt("value"));
        }
    }

    @Test
    void failedDumpKeepsEventsForAStopRetry(@TempDir Path directory) throws Exception {
        try (var recording = new Recording()) {
            recording.enable(Marker.class);
            recording.start();
            new Marker().commit();
            assertThrows(IOException.class, () -> MinecraftDebugService.stopRecording(recording, directory));
            assertEquals(RecordingState.STOPPED, recording.getState());
            Path output = directory.resolve("retry.jfr");
            MinecraftDebugService.stopRecording(recording, output);
            assertEquals(RecordingState.CLOSED, recording.getState());
            var events = RecordingFile.readAllEvents(output);
            assertEquals(1, events.size());
            assertEquals(42, events.getFirst().getInt("value"));
        }
    }
}
