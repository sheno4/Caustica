package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryProfiling;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtEntitiesPublicationStateTest {
    @Test
    void successfulPublicationMarksTheResidentPublished() {
        RtEntities.PublicationState state = new RtEntities.PublicationState();
        state.acknowledged();

        assertTrue(state.published);
    }

    @Test
    void submittedMeshHashRemainsStableUntilAnotherMeshIsSubmitted() {
        RtEntities.EntityState state = state();

        assertTrue(state.requiresPut(10L));
        state.meshSubmitted(10L);
        assertFalse(state.requiresPut(10L));
        assertTrue(state.requiresPut(20L));
    }

    @Test
    void initialResidentIsSubmittedOnlyOnceUntilPublication() {
        RtEntities.EntityState state = state();

        assertTrue(state.beginInitialSubmission(10L));
        assertFalse(state.beginInitialSubmission(20L));
        assertFalse(state.requiresPut(10L));
        assertTrue(state.requiresPut(20L));
    }

    @Test
    void publishedUnchangedEntitySubmitsOnlyPlacement() {
        RtEntities.EntityState state = state();
        state.meshSubmitted(10L);
        state.publication.acknowledged();

        assertFalse(state.requiresPut(10L));
    }

    @Test
    void publishedTopologyChangeRequiresANewMeshSubmission() {
        RtEntities.EntityState state = state();
        state.meshSubmitted(10L);
        state.publication.acknowledged();

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
        RtGeometryProfiling.resetPublications();
        long first = state.profileMeshSubmission();
        long sourceFrame = RtFrameStats.frameSerial();

        state.meshPublicationAccepted(first, sourceFrame, state.meshVisibilityToken);

        assertEquals(0L, state.visibleMeshVersion);
        assertEquals(0L, state.meshVisibilityCount);

        RtFrameStats.beginRenderFrame();
        RtGeometryProfiling.frameVisible();

        assertEquals(1L, state.visibleMeshVersion);
        assertEquals(1L, state.meshVisibilityCount);
        assertEquals(sourceFrame, state.visibleMeshSourceFrame);
        assertEquals(1L, state.initialUnavailableFrames);
        RtGeometryProfiling.resetPublications();
    }

    @Test
    void multipleMapPublicationsBeforeAFrameCountOnlyTheNewestVisibleMesh() {
        RtEntities.EntityState state = state();
        RtGeometryProfiling.resetPublications();
        long first = state.profileMeshSubmission();
        long second = state.profileMeshSubmission();

        state.meshPublicationAccepted(first, 100L, state.meshVisibilityToken);
        state.meshPublicationAccepted(second, 101L, state.meshVisibilityToken);
        RtGeometryProfiling.frameVisible();

        assertEquals(second, state.visibleMeshVersion);
        assertEquals(1L, state.meshVisibilityCount);
        assertEquals(101L, state.visibleMeshSourceFrame);
        RtGeometryProfiling.resetPublications();
    }

    @Test
    void firstDrainPutThenSecondDrainRemovalDoesNotCountMeshVisibility() {
        RtEntities.EntityState state = state();
        RtGeometryProfiling.resetPublications();
        long version = state.profileMeshSubmission();
        long token = state.meshVisibilityToken;

        state.meshPublicationAccepted(version, 100L, token);
        state.invalidateMeshVisibility();
        RtGeometryProfiling.frameVisible();

        assertEquals(0L, state.visibleMeshVersion);
        assertEquals(0L, state.meshVisibilityCount);
        assertFalse(state.meshVisibilityQueued);
        RtGeometryProfiling.resetPublications();
    }

    @Test
    void sourceClearInvalidatesADeferredMeshVisibilityCallback() {
        RtEntities.EntityState state = state();
        RtGeometryProfiling.resetPublications();
        long version = state.profileMeshSubmission();

        state.meshPublicationAccepted(version, 100L, state.meshVisibilityToken);
        state.invalidateMeshVisibility();
        RtGeometryProfiling.frameVisible();

        assertEquals(0L, state.meshVisibilityCount);
        assertEquals(0L, state.visibleMeshVersion);
        RtGeometryProfiling.resetPublications();
    }

    @Test
    void delayedRemovalPreservesTheInterimVisibleMeshSample() {
        RtEntities.EntityState state = state();
        RtGeometryProfiling.resetPublications();
        long version = state.profileMeshSubmission();
        long token = state.meshVisibilityToken;

        state.meshPublicationAccepted(version, 100L, token);
        RtGeometryProfiling.frameVisible();
        state.invalidateMeshVisibility();

        assertEquals(version, state.visibleMeshVersion);
        assertEquals(1L, state.meshVisibilityCount);
        assertFalse(state.meshVisibilityQueued);
        RtGeometryProfiling.resetPublications();
    }

    @Test
    void retiredLifecycleTokenRejectsALaterOldPutPublication() {
        RtEntities.EntityState state = state();
        RtGeometryProfiling.resetPublications();
        long version = state.profileMeshSubmission();
        long retiredToken = state.meshVisibilityToken;

        state.invalidateMeshVisibility();
        state.meshPublicationAccepted(version, 100L, retiredToken);
        RtGeometryProfiling.frameVisible();

        assertEquals(0L, state.meshVisibilityCount);
        assertFalse(state.meshVisibilityQueued);
        RtGeometryProfiling.resetPublications();
    }

    @Test
    void successfulFrameVisibilityTracksSkippedRevisionsIntervalAndPriorPoseAge() {
        RtEntities.EntityState state = state();
        state.visibleMeshVersion = 1L;
        state.meshVisibilityCount = 1L;
        state.lastMeshVisibilityFrame = 100L;
        state.visibleMeshSourceFrame = 98L;
        state.pendingVisibleMeshVersion = 4L;
        state.pendingVisibleMeshSourceFrame = 101L;
        state.pendingMeshVisibilityToken = state.meshVisibilityToken;
        state.meshVisibilityQueued = true;

        state.meshFrameVisible(104L, state.meshVisibilityToken);

        assertEquals(2L, state.meshRevisionsSkippedBetweenVisibility);
        assertEquals(4L, state.meshVisibilityIntervalFramesTotal);
        assertEquals(4L, state.meshVisibilityIntervalFramesMax);
        assertEquals(5L, state.priorPoseLastRenderedAgeFramesTotal);
        assertEquals(5L, state.priorPoseLastRenderedAgeFramesMax);
    }

    private static RtEntities.EntityState state() {
        return new RtEntities.EntityState(new UUID(1L, 2L));
    }
}
