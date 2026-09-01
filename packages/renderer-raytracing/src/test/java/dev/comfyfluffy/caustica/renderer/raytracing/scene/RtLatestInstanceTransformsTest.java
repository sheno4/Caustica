package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedInstanceTransform;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtLatestInstanceTransformsTest {
    private static final SceneId SCENE = new SceneId() { };
    private static final ShaderDataType<Object> INSTANCE_DATA = ShaderDataType.create("latest instance");

    @Test
    void multipleUpdatesCoalesceToTheLatestPlacement() {
        RtLatestInstanceTransforms transforms = new RtLatestInstanceTransforms();
        RetainedSceneSnapshot.Instance published = instance(1, 7, 0, 0xff);
        transforms.acceptSnapshot(List.of(published));

        transforms.acceptLatest(List.of(update(1, 1, 0xff)));
        transforms.acceptLatest(List.of(update(1, 2, 0x01)));
        RetainedSceneSnapshot.Instance resolved = transforms.resolve(published);

        assertEquals(GeometryTransform.translation(2, 0, 0), resolved.transform());
        assertEquals(0x01, resolved.mask());
        assertEquals(7, resolved.meshIdentity());
        assertEquals(SCENE, resolved.scene());
    }

    @Test
    void resolvingLatestPlacementDoesNotConsumeOrChangeIt() {
        RtLatestInstanceTransforms transforms = new RtLatestInstanceTransforms();
        RetainedSceneSnapshot.Instance published = instance(1, 7, 0, 0xff);
        transforms.acceptSnapshot(List.of(published));
        transforms.acceptLatest(List.of(update(1, 2, 0xff)));

        RetainedSceneSnapshot.Instance first = transforms.resolve(published);
        RetainedSceneSnapshot.Instance second = transforms.resolve(published);

        assertEquals(GeometryTransform.translation(2, 0, 0), first.transform());
        assertEquals(first, second);
    }

    private static RetainedInstanceTransform update(long identity, double x, int mask) {
        return new RetainedInstanceTransform(identity, GeometryTransform.translation(x, 0, 0), mask);
    }

    private static RetainedSceneSnapshot.Instance instance(long identity, long mesh, double x, int mask) {
        return new RetainedSceneSnapshot.Instance(identity, SCENE, mesh,
                GeometryTransform.translation(x, 0, 0), mask, INSTANCE_DATA.data(identity), List.of());
    }
}
