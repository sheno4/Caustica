package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSceneGeometryCompactionStateTest {
    @Test
    void providerPayloadPreservesPerGeometryBuildOptions() {
        GeometryUpdates.ProviderPayload payload = new GeometryUpdates.ProviderPayload(null,
                SceneGeometrySink.BuildOptions.MINIMIZE_MEMORY);

        assertTrue(payload.buildOptions().minimizeMemory());
        assertFalse(new GeometryUpdates.ProviderPayload(null).buildOptions().minimizeMemory());
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
