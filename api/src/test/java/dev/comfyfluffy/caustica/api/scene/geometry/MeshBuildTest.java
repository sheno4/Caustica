package dev.comfyfluffy.caustica.api.scene.geometry;

import dev.comfyfluffy.caustica.api.material.MaterialId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MeshBuildTest {
    @Test
    void streamOffsetParticipatesInTheEffectiveAddress() {
        var stream = new MeshBuild.Stream(0x1000L, 32L, 64L, 16);
        assertEquals(0x1020L, stream.deviceAddress());
    }

    @Test
    void forcedBuildDoesNotRequireTopologyIdentity() {
        MeshBuild build = build(MeshBuild.UpdateIntent.FORCE_REBUILD, null);
        assertNull(build.revision());
    }

    @Test
    void updateRequiresTopologyIdentity() {
        assertThrows(NullPointerException.class, () -> build(MeshBuild.UpdateIntent.ALLOW_UPDATE, null));
    }

    @Test
    void rejectsOverlappingGeometrySlices() {
        MaterialId material = new MaterialId() { };
        assertThrows(IllegalArgumentException.class, () -> new MeshBuild(
                new MeshBuild.Stream(0x1000L, 0L, 36L, 12), null,
                new MeshBuild.Stream(0x2000L, 0L, 24L, 4), 3,
                MeshBuild.UpdateIntent.FORCE_REBUILD, null,
                List.of(geometry(material, 0, 6), geometry(material, 3, 3))));
    }

    @Test
    void opacityHintAlwaysHasCoverageFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> new MeshBuild.OpacityMicromapHint(0.8f, 0.2f, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new MeshBuild.OpacityMicromapHint(0.2f, 0.8f, 13));
    }

    private static MeshBuild build(MeshBuild.UpdateIntent intent, MeshBuild.TopologyRevision revision) {
        MaterialId material = new MaterialId() { };
        return new MeshBuild(
                new MeshBuild.Stream(0x1000L, 0L, 36L, 12), null,
                new MeshBuild.Stream(0x2000L, 0L, 12L, 4), 3, intent, revision,
                List.of(geometry(material, 0, 3)));
    }

    private static MeshBuild.Geometry geometry(MaterialId material, int first, int count) {
        return new MeshBuild.Geometry(material, first, count, 0L, false,
                MeshBuild.GeometrySemantics.NONE, null);
    }
}
