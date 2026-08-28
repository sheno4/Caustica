package dev.comfyfluffy.caustica.api.frame;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class SceneFrameTest {
    @Test
    void frameScaleMustBeFiniteAndPositive() {
        SceneView view = new SceneView(new SceneId() { }, Camera.IDENTITY);
        SceneFrameWriter writer = mutation -> { };

        assertThrows(IllegalArgumentException.class,
                () -> new SceneFrame(view, 0, 0, 0, 0, 1, writer));
        assertThrows(IllegalArgumentException.class,
                () -> new SceneFrame(view, 0, 0, 0, Double.NaN, 1, writer));
    }

    @Test
    void sceneMutationRequiresAnOperation() {
        assertThrows(IllegalArgumentException.class,
                () -> SceneMutation.of(List.of(), List.of()));
    }
}
