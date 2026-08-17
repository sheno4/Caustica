package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialEpochBoundaryTest {
    @Test
    void lifecycleStateSeparatesReloadFromPublishedResourceDestruction() {
        RtMaterialEpoch.LifecycleState state = new RtMaterialEpoch.LifecycleState();
        state.published();
        assertTrue(state.bindingsReady);
        assertTrue(state.hasPublishedResources());

        state.beginReload();
        assertTrue(state.reloadPending);
        assertFalse(state.bindingsReady);
        assertTrue(state.hasPublishedResources(),
                "the active descriptors remain owned until the drained destruction phase");

        state.destroyPublished();
        assertTrue(state.reloadPending);
        assertFalse(state.hasPublishedResources());
        state.reloadFailed();
        assertFalse(state.reloadPending);
        assertFalse(state.bindingsReady);

        state.published();
        assertTrue(state.bindingsReady,
                "the next world rebuild republishes materials after the failed reload cleared the destroyed epoch");
    }

}
