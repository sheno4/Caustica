package dev.comfyfluffy.caustica.api.scene;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SceneContractTest {
    @Test
    void owningCapabilityIsNotADataReference() {
        assertFalse(SceneId.class.isAssignableFrom(OwnedScene.class));
    }

    @Test
    void renderViewAssociatesCameraWithARootScene() {
        SceneId scene = new SceneId() { };

        RenderView view = new RenderView(scene, SceneCamera.IDENTITY);

        assertSame(scene, view.rootScene());
        assertSame(SceneCamera.IDENTITY, view.camera());
    }

    @Test
    void frameScaleMustBeFiniteAndPositive() {
        RenderView view = new RenderView(new SceneId() { }, SceneCamera.IDENTITY);
        SceneFrameWriter writer = new SceneFrameWriter() {
            @Override public void submit(SceneMutation mutation) { }
            @Override public void submitTransient(List<AtomicBatch<TransientGeometry>> batches) { }
        };

        assertThrows(IllegalArgumentException.class,
                () -> new SceneFrameContext(view, 0, 0, 0, 0, 1, writer));
        assertThrows(IllegalArgumentException.class,
                () -> new SceneFrameContext(view, 0, 0, 0, Double.NaN, 1, writer));
    }

    @Test
    void sceneMutationRequiresAnOperation() {
        assertThrows(IllegalArgumentException.class,
                () -> SceneMutation.of(List.of(), List.of()));
    }
}
