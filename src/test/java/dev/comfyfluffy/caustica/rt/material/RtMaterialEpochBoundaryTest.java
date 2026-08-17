package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialEpochBoundaryTest {
    @Test
    void lifecycleStateSeparatesReloadFromPublishedResourceDestruction() {
        RtMaterialEpoch.LifecycleState state = new RtMaterialEpoch.LifecycleState();
        state.published(64);
        assertTrue(state.bindingsReady);
        assertTrue(state.hasPublishedResources());
        assertEquals(64, state.bindlessTextureCapacity);

        state.beginReload();
        assertTrue(state.reloadPending);
        assertFalse(state.bindingsReady);
        assertEquals(64, state.bindlessTextureCapacity,
                "the caller still needs the active pipeline capacity until descriptor destruction");

        state.destroyPublished();
        assertTrue(state.reloadPending);
        assertEquals(0, state.bindlessTextureCapacity);
        state.reloadFailed();
        assertFalse(state.reloadPending);
        assertFalse(state.bindingsReady);

        state.published(64);
        assertTrue(state.bindingsReady,
                "the next world rebuild republishes materials after the failed reload cleared the destroyed epoch");
    }

}
