package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
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
        SurfaceId surface = new SurfaceId() { };
        assertThrows(IllegalArgumentException.class, () -> new MeshBuild(
                new MeshBuild.Stream(0x1000L, 0L, 36L, 12), null,
                new MeshBuild.Stream(0x2000L, 0L, 24L, 4), 3,
                MeshBuild.UpdateIntent.FORCE_REBUILD, null,
                List.of(geometry(surface, 0, 6), geometry(surface, 3, 3))));
    }

    @Test
    void opacityHintAlwaysHasCoverageFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> new MeshBuild.OpacityMicromapHint(0.8f, 0.2f, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new MeshBuild.OpacityMicromapHint(0.2f, 0.8f, 13));
    }

    @Test
    void geometryRejectsInvalidTraversalCutoff() {
        SurfaceId surface = new SurfaceId() { };
        assertThrows(IllegalArgumentException.class,
                () -> new MeshBuild.SurfaceSlot(surface, Float.NaN, false, null));
    }

    @Test
    void geometryRequiresAtLeastOneShadingSlot() {
        assertThrows(IllegalArgumentException.class,
                () -> new MeshBuild.Geometry(null, null, 0, 3, 0L));
    }

    @Test
    void geometryAcceptsAnInvisibleVolumeBoundary() {
        VolumeId volume = new VolumeId() { };
        MeshBuild.Geometry geometry = new MeshBuild.Geometry(null, volume, 0, 3, 0L);

        assertNull(geometry.surface());
        assertEquals(volume, geometry.volume());
    }

    private static MeshBuild build(MeshBuild.UpdateIntent intent, MeshBuild.TopologyRevision revision) {
        SurfaceId surface = new SurfaceId() { };
        return new MeshBuild(
                new MeshBuild.Stream(0x1000L, 0L, 36L, 12), null,
                new MeshBuild.Stream(0x2000L, 0L, 12L, 4), 3, intent, revision,
                List.of(geometry(surface, 0, 3)));
    }

    private static MeshBuild.Geometry geometry(SurfaceId surface, int first, int count) {
        return new MeshBuild.Geometry(new MeshBuild.SurfaceSlot(surface, 0.5f, false, null),
                null, first, count, 0L);
    }
}
