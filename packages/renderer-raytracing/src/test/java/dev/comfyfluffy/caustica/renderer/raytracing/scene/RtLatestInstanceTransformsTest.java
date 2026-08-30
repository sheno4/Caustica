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
    void multipleUpdatesCoalesceToLatestWithPenultimateMotion() {
        RtLatestInstanceTransforms transforms = new RtLatestInstanceTransforms();
        RetainedSceneSnapshot.Instance published = instance(1, 7, 0, 0xff);
        transforms.acceptSnapshot(List.of(published));

        transforms.acceptLatest(List.of(update(1, 1, 0xff)));
        transforms.acceptLatest(List.of(update(1, 2, 0x01)));
        RtLatestInstanceTransforms.Resolved resolved = transforms.latch(
                published, published.transform());

        assertEquals(GeometryTransform.translation(2, 0, 0), resolved.instance().transform());
        assertEquals(GeometryTransform.translation(1, 0, 0), resolved.previous());
        assertEquals(0x01, resolved.instance().mask());
        assertEquals(7, resolved.instance().meshIdentity());
        assertEquals(SCENE, resolved.instance().scene());
    }

    @Test
    void aConsumedStationaryPlacementDoesNotReplayMotion() {
        RtLatestInstanceTransforms transforms = new RtLatestInstanceTransforms();
        RetainedSceneSnapshot.Instance published = instance(1, 7, 0, 0xff);
        transforms.acceptSnapshot(List.of(published));
        transforms.acceptLatest(List.of(update(1, 2, 0xff)));
        assertEquals(GeometryTransform.translation(0, 0, 0),
                transforms.latch(published, published.transform()).previous());

        RtLatestInstanceTransforms.Resolved stationary = transforms.latch(
                published, published.transform());

        assertEquals(GeometryTransform.translation(2, 0, 0), stationary.instance().transform());
        assertEquals(stationary.instance().transform(), stationary.previous());
    }

    private static RetainedInstanceTransform update(long identity, double x, int mask) {
        return new RetainedInstanceTransform(identity, GeometryTransform.translation(x, 0, 0), mask);
    }

    private static RetainedSceneSnapshot.Instance instance(long identity, long mesh, double x, int mask) {
        return new RetainedSceneSnapshot.Instance(identity, SCENE, mesh,
                GeometryTransform.translation(x, 0, 0), mask, INSTANCE_DATA.data(identity), List.of());
    }
}
