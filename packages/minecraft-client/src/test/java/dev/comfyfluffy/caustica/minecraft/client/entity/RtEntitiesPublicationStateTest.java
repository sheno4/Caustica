package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetryImpl;
import org.junit.jupiter.api.Test;

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
    void initialResidentRequiresSubmissionEvenWhenItsHashIsZero() {
        RtEntities.EntityState state = state();

        assertTrue(state.requiresPut(0L));
        state.meshSubmitted(0L);
        assertFalse(state.requiresPut(0L));
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
        telemetry.reset();
    }

    @Test
    void successfulFrameVisibilityRetainsSourceRevisionAndFrame() {
        RtEntities.EntityState state = state();
        state.visibleMeshVersion = 1L;
        state.meshVisibilityCount = 1L;
        state.lastMeshVisibilityFrame = 100L;
        state.visibleMeshSourceFrame = 98L;
        state.meshFrameVisible(104L, state.meshVisibilityToken, 4L, 101L);
        assertEquals(104L, state.lastMeshVisibilityFrame);
        assertEquals(101L, state.visibleMeshSourceFrame);

    }

    @Test
    void adjacentPublicationsRetainTheirSourceFrame() {
        RtEntities.EntityState state = state();
        state.visibleMeshVersion = 1L;
        state.meshVisibilityCount = 1L;
        state.lastMeshVisibilityFrame = 101L;
        state.visibleMeshSourceFrame = 100L;
        state.meshFrameVisible(102L, state.meshVisibilityToken, 2L, 101L);
        assertEquals(102L, state.lastMeshVisibilityFrame);
        assertEquals(2L, state.visibleMeshVersion);

    }

    @Test
    void newerAcceptedMeshWaitsForThePreparedRevisionThatContainsIt() {
        var state = state();
        long first = state.profileMeshSubmission();
        state.meshPublicationAccepted(first, 100L, state.meshVisibilityToken, telemetry);
        long cutoff = telemetry.renderer.publicationCutoff();
        long second = state.profileMeshSubmission();
        state.meshPublicationAccepted(second, 101L, state.meshVisibilityToken, telemetry);

        telemetry.renderer.beginRenderFrame();
        telemetry.renderer.frameAssembled(cutoff);
        assertEquals(first, state.visibleMeshVersion);
        assertEquals(100L, state.visibleMeshSourceFrame);
        assertEquals(1L, state.meshVisibilityCount);

        telemetry.advanceVisibleFrame();
        assertEquals(second, state.visibleMeshVersion);
        assertEquals(101L, state.visibleMeshSourceFrame);
        assertEquals(2L, state.meshVisibilityCount);
    }

    private static RtEntities.EntityState state() {
        return new RtEntities.EntityState(new UUID(1L, 2L));
    }

    private static final class TestTelemetry implements MinecraftTelemetry.Instrumentation {
        private final RtTelemetryImpl renderer = new RtTelemetryImpl();

        @Override public boolean enabled() { return true; }
        @Override public long frameSerial() { return renderer.frameSerial(); }
        @Override public long startStage() { return 0L; }
        @Override public void endStage(String name, long startedNanos) { }
        @Override public void count(String name, long delta) { }
        @Override public void set(String name, long value) { }
        @Override public Object extraction(MinecraftTelemetry.GeometrySource source, int geometryCount) { return null; }
        @Override public void published(Object stamp) { }
        @Override public void afterPublicationVisible(LongConsumer action) { renderer.afterPublicationVisible(action); }
        @Override public void afterPublicationVisible(Object identity, LongConsumer action) {
            renderer.afterPublicationVisible(identity, action);
        }

        void advanceVisibleFrame() {
            renderer.endFrame();
            renderer.beginRenderFrame();
            publishVisible();
        }

        void publishVisible() {
            renderer.frameAssembled(renderer.publicationCutoff());
        }

        void reset() {
            renderer.resetPublications();
        }
    }

}
