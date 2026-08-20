package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtSceneGeometryTopologyPolicyTest {
    private static final RtSceneGeometryManager.ResidentId RESIDENT = new RtSceneGeometryManager.ResidentId(
            ResourceId.of("test", "source"), SceneGeometryKey.of(7));

    @Test
    void equalRevisionAndStructurePermitBlasUpdateAndPositionHistory() {
        var previous = topology(11, 4, 6, 2, 0, 0, 3);
        var candidate = topology(11, 4, 6, 2, 0, 0, 3);

        assertEquals(RtSceneGeometryManager.TopologyPolicy.REUSE_AND_UPDATE,
                RtSceneGeometryManager.topologyPolicy(RESIDENT, previous, candidate));
    }

    @Test
    void equalRevisionRejectsProviderOwnedStructuralMismatch() {
        var previous = topology(11, 4, 6, 2, 0, 0, 3);
        List<SceneMeshPacker.Topology> incompatible = List.of(
                topology(11, 5, 6, 2, 0, 0, 3),
                topology(11, 4, 9, 2, 0, 0, 3),
                topology(11, 4, 6, 2, 0, 0, 5));

        for (SceneMeshPacker.Topology candidate : incompatible) {
            assertThrows(IllegalArgumentException.class,
                    () -> RtSceneGeometryManager.topologyPolicy(RESIDENT, previous, candidate));
        }
    }

    @Test
    void engineClassPartitionChangeRebuildsBlasButPreservesVertexHistory() {
        var previous = topology(11, 4, 6, 2, 0, 0, 3);
        var candidate = topology(11, 4, 6, 1, 1, 0, 3);

        assertEquals(RtSceneGeometryManager.TopologyPolicy.REBUILD_PRESERVE_HISTORY,
                RtSceneGeometryManager.topologyPolicy(RESIDENT, previous, candidate));
    }

    @Test
    void changedRevisionRebuildsEvenWhenStructureIsIdentical() {
        assertEquals(RtSceneGeometryManager.TopologyPolicy.REBUILD_AND_RESET,
                RtSceneGeometryManager.topologyPolicy(RESIDENT,
                        topology(11, 4, 6, 2, 0, 0, 3),
                        topology(12, 4, 6, 2, 0, 0, 3)));
    }

    @Test
    void absentRevisionConservativelyRebuilds() {
        var stable = topology(11, 4, 6, 2, 0, 0, 3);
        var absent = topology(null, 4, 6, 2, 0, 0, 3);

        assertEquals(RtSceneGeometryManager.TopologyPolicy.REBUILD_AND_RESET,
                RtSceneGeometryManager.topologyPolicy(RESIDENT, stable, absent));
        assertEquals(RtSceneGeometryManager.TopologyPolicy.REBUILD_AND_RESET,
                RtSceneGeometryManager.topologyPolicy(RESIDENT, absent, stable));
        assertEquals(RtSceneGeometryManager.TopologyPolicy.REBUILD_AND_RESET,
                RtSceneGeometryManager.topologyPolicy(RESIDENT, absent, absent));
        assertEquals(RtSceneGeometryManager.TopologyPolicy.REBUILD_AND_RESET,
                RtSceneGeometryManager.topologyPolicy(RESIDENT, null, stable));
    }

    private static SceneMeshPacker.Topology topology(long revision, int vertices, int indices,
                                                     int opaque, int masked, int transmissive, int flags) {
        return topology(new SceneMesh.TopologyRevision(revision), vertices, indices,
                opaque, masked, transmissive, flags);
    }

    private static SceneMeshPacker.Topology topology(SceneMesh.TopologyRevision revision,
                                                     int vertices, int indices,
                                                     int opaque, int masked, int transmissive, int flags) {
        return new SceneMeshPacker.Topology(revision, vertices, indices,
                opaque, masked, transmissive, flags);
    }
}
