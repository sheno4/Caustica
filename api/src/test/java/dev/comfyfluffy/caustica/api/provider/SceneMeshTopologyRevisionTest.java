package dev.comfyfluffy.caustica.api.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SceneMeshTopologyRevisionTest {
    @Test
    void existingConstructorsExplicitlyDeclineTopologyReuse() {
        assertNull(mesh(null).topologyRevision());
    }

    @Test
    void preservesProviderTopologyRevisionByValue() {
        SceneMesh.TopologyRevision revision = new SceneMesh.TopologyRevision(0x1020_3040_5060_7080L);

        assertEquals(revision, mesh(revision).topologyRevision());
    }

    private static SceneMesh mesh(SceneMesh.TopologyRevision revision) {
        return new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6],
                List.of(SceneMesh.TriangleSurface.surface(MaterialHandle.of("test", "material"))),
                Set.of(), revision);
    }
}
