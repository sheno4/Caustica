package dev.comfyfluffy.caustica.minecraft.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    private static RtEntities.EntityState state() {
        return new RtEntities.EntityState(new UUID(1L, 2L));
    }
}
