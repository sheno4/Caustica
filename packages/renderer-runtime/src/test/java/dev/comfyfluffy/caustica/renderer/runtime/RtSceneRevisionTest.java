package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtSceneRevisionTest {
    @Test
    void failedTraceReleaseStillReleasesScenesAndProgram() {
        var released = new ArrayList<String>();
        var failure = new IllegalStateException("trace release");
        var revision = new RtSceneRevision(
                release(() -> released.add("program")),
                release(() -> released.add("scenes")),
                release(() -> { released.add("trace"); throw failure; }), 0);

        assertSame(failure, assertThrows(IllegalStateException.class, revision::close));
        assertEquals(List.of("trace", "scenes", "program"), released);
        assertDoesNotThrow(revision::close);
    }

    // This fixture exercises claim disposal without constructing GPU values; close never reads them.
    @SuppressWarnings("unchecked")
    private static <T> SharedResource<T> release(Runnable action) {
        return (SharedResource<T>) (SharedResource<?>) SharedResource.owned(new Object(), ignored -> action.run());
    }
}
