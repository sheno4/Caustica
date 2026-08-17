package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class RtEntitiesBlockEntityUpdateTest {
    @Test
    void initialMeshCreatesItsPlacement() {
        SceneGeometryKey key = SceneGeometryKey.of(7);

        List<SceneGeometrySink.Operation> update = RtEntities.blockEntityMeshUpdate(
                key, mesh(), GeometryTransform.translation(10, 20, 30), true);

        assertEquals(2, update.size());
        assertInstanceOf(SceneGeometrySink.Put.class, update.get(0));
        SceneGeometrySink.Place place = assertInstanceOf(SceneGeometrySink.Place.class, update.get(1));
        assertEquals(key, place.instanceKey());
        assertEquals(key, place.residentKey());
        assertEquals(GeometryTransform.translation(10, 20, 30), place.transform());
    }

    @Test
    void changedMeshOnlyReplacesItsResident() {
        SceneGeometryKey key = SceneGeometryKey.of(7);

        List<SceneGeometrySink.Operation> update = RtEntities.blockEntityMeshUpdate(
                key, mesh(), GeometryTransform.translation(10, 20, 30), false);

        assertEquals(1, update.size());
        assertInstanceOf(SceneGeometrySink.Put.class, update.getFirst());
    }

    private static SceneMesh mesh() {
        return new SceneMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[] {0, 0, 1, 0, 0, 1},
                List.of(SceneMesh.TriangleSurface.surface(
                        new MaterialHandle(ResourceId.of("test", "block_entity")))));
    }
}
