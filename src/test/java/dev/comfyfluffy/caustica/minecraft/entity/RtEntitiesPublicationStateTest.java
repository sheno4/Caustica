package dev.comfyfluffy.caustica.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtEntitiesPublicationStateTest {
    @Test
    void anySuccessfulPutPublishesWhileOnlyTheLatestAcknowledgmentClearsPending() {
        RtEntities.PublicationState state = new RtEntities.PublicationState();
        state.submitted(1, true);
        state.submitted(2, false);

        state.acknowledged(1);

        assertTrue(state.published);
        assertTrue(state.putPending);

        state.acknowledged(2);

        assertFalse(state.putPending);
    }
}
