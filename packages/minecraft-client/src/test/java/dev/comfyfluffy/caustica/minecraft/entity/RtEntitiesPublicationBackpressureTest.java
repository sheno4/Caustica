package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.api.geometry.GeometryPublication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtEntitiesPublicationBackpressureTest {
    @Test
    void pendingReceiptBlocksTheNextCaptureGroup() {
        boolean[] visible = {false};
        GeometryPublication publication = () -> visible[0];

        assertTrue(RtEntities.publicationPending(publication));
        visible[0] = true;
        assertFalse(RtEntities.publicationPending(publication));
    }
}
