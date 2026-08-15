package dev.comfyfluffy.caustica.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtEntitiesPublicationStateTest {
    @Test
    void successfulPublicationMarksTheResidentPublished() {
        RtEntities.PublicationState state = new RtEntities.PublicationState();
        state.acknowledged();

        assertTrue(state.published);
    }
}
