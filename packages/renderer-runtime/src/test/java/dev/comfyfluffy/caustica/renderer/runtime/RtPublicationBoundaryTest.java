package dev.comfyfluffy.caustica.renderer.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPublicationBoundaryTest {
    @Test
    void keyedAcknowledgmentsCoalesceOnlyWithinTheDisplayedRevisionCutoff() {
        var telemetry = new RtTelemetryImpl();
        var assembled = new ArrayList<String>();
        var identity = new Object();
        telemetry.beginRenderFrame();
        telemetry.afterPublicationVisible(identity, frame -> assembled.add("obsolete:" + frame));
        telemetry.afterPublicationVisible(frame -> assembled.add("unkeyed:" + frame));
        telemetry.afterPublicationVisible(identity, frame -> assembled.add("captured:" + frame));
        long cutoff = telemetry.publicationCutoff();
        telemetry.afterPublicationVisible(identity, frame -> assembled.add("pending:" + frame));

        telemetry.frameAssembled(cutoff);
        assertEquals(List.of("unkeyed:1", "captured:1"), assembled);
        telemetry.frameAssembled(cutoff);
        assertEquals(List.of("unkeyed:1", "captured:1"), assembled);

        telemetry.endFrame();
        telemetry.beginRenderFrame();
        telemetry.frameAssembled(telemetry.publicationCutoff());
        assertEquals(List.of("unkeyed:1", "captured:1", "pending:2"), assembled);
    }

    @Test
    void equalButDistinctPublicationIdentitiesRemainIndependent() {
        var telemetry = new RtTelemetryImpl();
        var assembled = new ArrayList<String>();
        telemetry.afterPublicationVisible(new String("entity"), frame -> assembled.add("first"));
        telemetry.afterPublicationVisible(new String("entity"), frame -> assembled.add("second"));
        telemetry.frameAssembled(telemetry.publicationCutoff());
        assertEquals(List.of("first", "second"), assembled);
    }

    @Test
    void keyedCallbackQueuedDuringVisibilityWaitsForANewCapture() {
        var telemetry = new RtTelemetryImpl();
        var assembled = new ArrayList<String>();
        var identity = new Object();
        telemetry.afterPublicationVisible(identity, frame -> {
            assembled.add("captured");
            telemetry.afterPublicationVisible(identity, later -> assembled.add("next"));
        });
        long cutoff = telemetry.publicationCutoff();
        telemetry.frameAssembled(cutoff);
        assertEquals(List.of("captured"), assembled);
        telemetry.frameAssembled(telemetry.publicationCutoff());
        assertEquals(List.of("captured", "next"), assembled);
    }

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
