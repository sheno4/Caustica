package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSceneGeometryCompactionStateTest {
    @Test
    void providerPayloadPreservesInternalBuildPolicy() {
        GeometryUpdates.ProviderPayload payload = new GeometryUpdates.ProviderPayload(null,
                GeometryUpdates.BuildPolicy.STATIC);

        assertEquals(GeometryUpdates.BuildPolicy.STATIC, payload.buildPolicy());
        assertEquals(GeometryUpdates.BuildPolicy.DYNAMIC,
                new GeometryUpdates.ProviderPayload(null, GeometryUpdates.BuildPolicy.DYNAMIC).buildPolicy());
    }

    @Test
    void compactableCandidateCannotPublishAfterOnlyTheBuild() {
        RtSceneGeometryManager.CompactionPublicationState state =
                new RtSceneGeometryManager.CompactionPublicationState(true);

        state.completeBuild();

        assertEquals(RtSceneGeometryManager.CompactionPublicationState.Phase.COMPACT, state.phase());
        assertFalse(state.publishable());
        state.completeCompaction();
        assertEquals(RtSceneGeometryManager.CompactionPublicationState.Phase.TERMINAL, state.phase());
        assertTrue(state.publishable());
    }

    @Test
    void failureOrCancellationNeverBecomesPublishable() {
        RtSceneGeometryManager.CompactionPublicationState buildFailure =
                new RtSceneGeometryManager.CompactionPublicationState(true);
        buildFailure.fail();
        assertFalse(buildFailure.publishable());

        RtSceneGeometryManager.CompactionPublicationState compactCancellation =
                new RtSceneGeometryManager.CompactionPublicationState(true);
        compactCancellation.completeBuild();
        compactCancellation.fail();
        assertEquals(RtSceneGeometryManager.CompactionPublicationState.Phase.TERMINAL,
                compactCancellation.phase());
        assertFalse(compactCancellation.publishable());
    }

    @Test
    void defaultCandidatePublishesAtBuildTerminal() {
        RtSceneGeometryManager.CompactionPublicationState state =
                new RtSceneGeometryManager.CompactionPublicationState(false);

        state.completeBuild();

        assertTrue(state.publishable());
    }
}
