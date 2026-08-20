package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class GeometryUpdatesTopologyRevisionTest {
    @Test
    void providerPayloadCarriesTheMeshTopologyRevision() {
        SceneMesh.TopologyRevision revision = new SceneMesh.TopologyRevision(17L);

        assertEquals(revision, new GeometryUpdates.ProviderPayload(mesh(revision),
                GeometryUpdates.BuildPolicy.DYNAMIC).topologyRevision());
        assertNull(new GeometryUpdates.ProviderPayload(mesh(null),
                GeometryUpdates.BuildPolicy.STATIC).topologyRevision());
    }

    private static SceneMesh mesh(SceneMesh.TopologyRevision revision) {
        return new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6],
                List.of(SceneMesh.TriangleSurface.surface(MaterialHandle.of("test", "material"))),
                Set.of(), revision);
    }
}
