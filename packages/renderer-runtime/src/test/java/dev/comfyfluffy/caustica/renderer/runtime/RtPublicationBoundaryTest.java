package dev.comfyfluffy.caustica.renderer.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPublicationBoundaryTest {
    @Test
    void laterPublicationsWaitForTheNextCapturedFrame() {
        var telemetry = new RtTelemetryImpl();
        var assembled = new ArrayList<String>();
        telemetry.beginRenderFrame();
        telemetry.afterPublicationVisible(frame -> {
            assembled.add("first:" + frame);
            telemetry.afterPublicationVisible(later -> assembled.add("callback:" + later));
        });
        long cutoff = telemetry.publicationCutoff();
        telemetry.afterPublicationVisible(frame -> assembled.add("later:" + frame));

        telemetry.frameAssembled(cutoff);
        assertEquals(List.of("first:1"), assembled);
        telemetry.frameAssembled(cutoff);
        assertEquals(List.of("first:1"), assembled);

        telemetry.endFrame();
        telemetry.beginRenderFrame();
        telemetry.frameAssembled(telemetry.publicationCutoff());
        assertEquals(List.of("first:1", "later:2", "callback:2"), assembled);
    }

    @Test
    void unloadDropsOldWorldCallbacks() {
        var telemetry = new RtTelemetryImpl();
        var assembled = new ArrayList<String>();
        telemetry.afterPublicationVisible(frame -> assembled.add("old:" + frame));
        long oldCutoff = telemetry.publicationCutoff();
        telemetry.resetPublications();
        telemetry.afterPublicationVisible(frame -> assembled.add("new:" + frame));
        telemetry.beginRenderFrame();

        telemetry.frameAssembled(oldCutoff);
        assertEquals(List.of(), assembled);
        telemetry.frameAssembled(telemetry.publicationCutoff());
        assertEquals(List.of("new:1"), assembled);
    }
}
