package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.spi.host.RendererRuntimeStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtRuntimeStatusTest {
    @Test
    void inactiveRuntimePublishesHostStatusWithoutRendererTypes() {
        RendererRuntimeStatus status = RtRuntime.INSTANCE.status();

        assertEquals(RendererRuntimeStatus.State.OFF, status.state());
        assertFalse(status.active());
        assertFalse(status.frameActive());
        assertFalse(status.sessionPresent());
        assertFalse(status.rendererFailed());
        assertEquals(RendererRuntimeStatus.WorldReplacement.FRAME_INACTIVE, status.worldReplacement());
    }
}
