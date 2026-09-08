package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtSceneRequestTest {
    @Test void repeatedCaptureReusesTheRevisionKeyButDistinctPublicationsDoNot() {
        try (var first = scene(); var second = scene();
             var captured = RtSceneRequest.capture(() -> null, first::retain, () -> 0, SceneOrigin.ZERO, 1);
             var repeated = RtSceneRequest.capture(() -> null, first::retain, () -> 0, SceneOrigin.ZERO, 1);
             var replacement = RtSceneRequest.capture(() -> null, second::retain, () -> 0, SceneOrigin.ZERO, 1)) {
            assertEquals(first.get(), second.get());
            assertEquals(captured.key(), repeated.key());
            assertEquals(captured.key().hashCode(), repeated.key().hashCode());
            assertNotEquals(captured.key(), replacement.key());
        }
    }

    @Test void newAcknowledgmentsNeedPublicationEvenWhenSceneContentsAreUnchanged() {
        try (var scene = scene();
             var before = RtSceneRequest.capture(() -> null, scene::retain, () -> 1, SceneOrigin.ZERO, 1);
             var after = RtSceneRequest.capture(() -> null, scene::retain, () -> 2, SceneOrigin.ZERO, 1)) {
            assertSame(before.scenes().get(), after.scenes().get());
            assertNotEquals(before.key(), after.key());
        }
    }

    private static SharedResource<RetainedSceneSnapshot> scene() {
        return SharedResource.owned(new RetainedSceneSnapshot(1, List.of(), List.of(), List.of(), List.of()),
                ignored -> { });
    }
}
