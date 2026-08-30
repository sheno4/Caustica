package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtEntitiesPublicationStateTest {
    private final TestTelemetry telemetry = new TestTelemetry();

    @Test
    void submittedMeshHashRemainsStableUntilAnotherMeshIsSubmitted() {
        RtEntities.EntityState state = state();

        assertTrue(state.requiresPut(10L));
        state.meshSubmitted(10L);
        assertFalse(state.requiresPut(10L));
        assertTrue(state.requiresPut(20L));
    }

    @Test
    void initialResidentIsSubmittedOnlyOnce() {
        RtEntities.EntityState state = state();

        assertTrue(state.beginInitialSubmission(10L));
        assertFalse(state.beginInitialSubmission(20L));
        assertFalse(state.requiresPut(10L));
        assertTrue(state.requiresPut(20L));
    }

    @Test
    void submittedUnchangedEntityDoesNotRequireAnotherMesh() {
        RtEntities.EntityState state = state();
        state.meshSubmitted(10L);

        assertFalse(state.requiresPut(10L));
    }

    @Test
    void submittedTopologyChangeRequiresANewMeshSubmission() {
        RtEntities.EntityState state = state();
        state.meshSubmitted(10L);

        assertTrue(state.requiresPut(20L));
        state.meshSubmitted(20L);
        assertFalse(state.requiresPut(20L));
    }

    @Test
    void numericIdReuseIsDetectedInsideTheCacheWindow() {
        UUID first = new UUID(1L, 2L);
        UUID reused = new UUID(3L, 4L);
        RtEntities.EntityState state = new RtEntities.EntityState(first);

        assertTrue(state.identity.equals(first));
        assertFalse(state.identity.equals(reused));
    }

    @Test
    void mapPublicationWaitsForTheFrameVisibilityBoundary() {
        RtEntities.EntityState state = state();
        telemetry.reset();
        long first = state.profileMeshSubmission();
        long sourceFrame = telemetry.frameSerial();

        state.meshPublicationAccepted(first, sourceFrame, state.meshVisibilityToken, telemetry);

        assertEquals(0L, state.visibleMeshVersion);
        assertEquals(0L, state.meshVisibilityCount);

        telemetry.advanceVisibleFrame();

        assertEquals(1L, state.visibleMeshVersion);
        assertEquals(1L, state.meshVisibilityCount);
        assertEquals(sourceFrame, state.visibleMeshSourceFrame);
        assertEquals(1L, state.initialUnavailableFrames);
        telemetry.reset();
    }

    @Test
    void multipleMapPublicationsBeforeAFrameCountOnlyTheNewestVisibleMesh() {
        RtEntities.EntityState state = state();
        telemetry.reset();
        long first = state.profileMeshSubmission();
        long second = state.profileMeshSubmission();

        state.meshPublicationAccepted(first, 100L, state.meshVisibilityToken, telemetry);
        state.meshPublicationAccepted(second, 101L, state.meshVisibilityToken, telemetry);
        telemetry.publishVisible();

        assertEquals(second, state.visibleMeshVersion);
        assertEquals(1L, state.meshVisibilityCount);
        assertEquals(101L, state.visibleMeshSourceFrame);
        telemetry.reset();
    }

    @Test
    void firstDrainPutThenSecondDrainRemovalDoesNotCountMeshVisibility() {
        RtEntities.EntityState state = state();
        telemetry.reset();
        long version = state.profileMeshSubmission();
        long token = state.meshVisibilityToken;

        state.meshPublicationAccepted(version, 100L, token, telemetry);
        state.invalidateMeshVisibility();
        telemetry.publishVisible();

        assertEquals(0L, state.visibleMeshVersion);
        assertEquals(0L, state.meshVisibilityCount);
        assertFalse(state.meshVisibilityQueued);
        telemetry.reset();
    }

    @Test
    void sourceClearInvalidatesADeferredMeshVisibilityCallback() {
        RtEntities.EntityState state = state();
        telemetry.reset();
        long version = state.profileMeshSubmission();

        state.meshPublicationAccepted(version, 100L, state.meshVisibilityToken, telemetry);
        state.invalidateMeshVisibility();
        telemetry.publishVisible();

        assertEquals(0L, state.meshVisibilityCount);
        assertEquals(0L, state.visibleMeshVersion);
        telemetry.reset();
    }

    @Test
    void delayedRemovalPreservesTheInterimVisibleMeshSample() {
        RtEntities.EntityState state = state();
        telemetry.reset();
        long version = state.profileMeshSubmission();
        long token = state.meshVisibilityToken;

        state.meshPublicationAccepted(version, 100L, token, telemetry);
        telemetry.publishVisible();
        state.invalidateMeshVisibility();

        assertEquals(version, state.visibleMeshVersion);
        assertEquals(1L, state.meshVisibilityCount);
        assertFalse(state.meshVisibilityQueued);
        telemetry.reset();
    }

    @Test
    void retiredLifecycleTokenRejectsALaterOldPutPublication() {
        RtEntities.EntityState state = state();
        telemetry.reset();
        long version = state.profileMeshSubmission();
        long retiredToken = state.meshVisibilityToken;

        state.invalidateMeshVisibility();
        state.meshPublicationAccepted(version, 100L, retiredToken, telemetry);
        telemetry.publishVisible();

        assertEquals(0L, state.meshVisibilityCount);
        assertFalse(state.meshVisibilityQueued);
        telemetry.reset();
    }

    @Test
    void successfulFrameVisibilityTracksSkippedRevisionsIntervalAndPriorRevisionLongevity() {
        RtEntities.EntityState state = state();
        state.visibleMeshVersion = 1L;
        state.meshVisibilityCount = 1L;
        state.lastMeshVisibilityFrame = 100L;
        state.visibleMeshSourceFrame = 98L;
        state.pendingVisibleMeshVersion = 4L;
        state.pendingVisibleMeshSourceFrame = 101L;
        state.pendingMeshVisibilityToken = state.meshVisibilityToken;
        state.meshVisibilityQueued = true;

        state.meshFrameVisible(104L, state.meshVisibilityToken, telemetry);

        assertEquals(2L, state.meshRevisionsSkippedBetweenVisibility);
        assertEquals(4L, state.meshVisibilityIntervalFramesTotal);
        assertEquals(4L, state.meshVisibilityIntervalFramesMax);
        assertEquals(5L, state.priorRevisionInterveningFramesAtReplacementTotal);
        assertEquals(5L, state.priorRevisionInterveningFramesAtReplacementMax);
    }

    @Test
    void adjacentOneFramePublicationsHaveOneInterveningFrameFromThePriorSourceRevision() {
        RtEntities.EntityState state = state();
        state.visibleMeshVersion = 1L;
        state.meshVisibilityCount = 1L;
        state.lastMeshVisibilityFrame = 101L;
        state.visibleMeshSourceFrame = 100L;
        state.pendingVisibleMeshVersion = 2L;
        state.pendingVisibleMeshSourceFrame = 101L;
        state.pendingMeshVisibilityToken = state.meshVisibilityToken;
        state.meshVisibilityQueued = true;

        state.meshFrameVisible(102L, state.meshVisibilityToken, telemetry);

        assertEquals(1L, state.priorRevisionInterveningFramesAtReplacementTotal);
        assertEquals(1L, state.priorRevisionInterveningFramesAtReplacementMax);
    }

    private static RtEntities.EntityState state() {
        return new RtEntities.EntityState(new UUID(1L, 2L));
    }

    private static final class TestTelemetry implements MinecraftTelemetry.Instrumentation {
        private final ArrayDeque<LongConsumer> visibilityActions = new ArrayDeque<>();
        private long frameSerial;

        @Override public boolean enabled() { return true; }
        @Override public long frameSerial() { return frameSerial; }
        @Override public long startStage() { return 0L; }
        @Override public void endStage(String name, long startedNanos) { }
        @Override public void count(String name, long delta) { }
        @Override public void set(String name, long value) { }
        @Override public void max(String name, long value) { }
        @Override public Object extraction(MinecraftTelemetry.GeometrySource source, int geometryCount) { return null; }
        @Override public void published(Object stamp) { }
        @Override public void afterPublicationVisible(LongConsumer action) { visibilityActions.add(action); }

        void advanceVisibleFrame() {
            frameSerial++;
            publishVisible();
        }

        void publishVisible() {
            LongConsumer action;
            while ((action = visibilityActions.poll()) != null) action.accept(frameSerial);
        }

        void reset() {
            visibilityActions.clear();
        }
    }

}
