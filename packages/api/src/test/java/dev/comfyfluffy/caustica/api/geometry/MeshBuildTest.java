package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MeshBuildTest {
    private interface Binding { }
    private interface Instance { }

    private static final ShaderDataType<Binding> BINDING = ShaderDataType.create("test binding");

    @Test
    void streamOffsetParticipatesInTheEffectiveAddress() {
        var stream = new MeshBuild.Stream(range(0x1000L, 96L).slice(32L, 64L), 16);
        assertEquals(new VulkanDeviceAddress(0x1020L), stream.bytes().address());
        assertEquals(64L, stream.byteSize());
    }

    @Test
    void streamDoesNotReExposeItsTypedRangeAsRawAddressBits() {
        assertFalse(java.util.Arrays.stream(MeshBuild.Stream.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("deviceAddress")));
    }

    @Test
    void rejectsPositionStrideThatIsNotAFloatMultiple() {
        assertThrows(IllegalArgumentException.class, () -> new MeshBuild<Instance>(
                new MeshBuild.Stream(range(0x1000L, 38L), 13), null,
                new MeshBuild.Stream(range(0x2000L, 12L), 4), 3, null,
                List.of(geometry(new SurfaceId<Binding, Instance>() { }, 0, 3))));
    }

    @Test
    void absentIndexRevisionRequiresRebuild() {
        MeshBuild<Instance> build = build(null);
        assertNull(build.indexRevision());
    }

    @Test
    void indexRevisionIsOptional() {
        assertEquals(7L, build(new MeshBuild.IndexRevision(7L)).indexRevision().value());
    }

    @Test
    void rejectsOverlappingGeometrySlices() {
        SurfaceId<Binding, Instance> surface = new SurfaceId<>() { };
        assertThrows(IllegalArgumentException.class, () -> new MeshBuild<Instance>(
                new MeshBuild.Stream(range(0x1000L, 36L), 12), null,
                new MeshBuild.Stream(range(0x2000L, 24L), 4), 3,
                null,
                List.of(geometry(surface, 0, 6), geometry(surface, 3, 3))));
    }

    @Test
    void geometryRejectsInvalidTraversalCutoff() {
        SurfaceId<Binding, Instance> surface = new SurfaceId<>() { };
        assertThrows(IllegalArgumentException.class,
                () -> new MeshBuild.CoveragePolicy.Cutout(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new MeshBuild.CoveragePolicy.Stochastic(1.01f));
    }

    @Test
    void surfaceSlotRequiresOneCompleteCoveragePolicy() {
        SurfaceId<Binding, Instance> surface = new SurfaceId<>() { };
        assertThrows(NullPointerException.class,
                () -> new MeshBuild.SurfaceSlot<>(surface, BINDING.data(0L), null));
        assertEquals(MeshBuild.CoveragePolicy.Opaque.class,
                new MeshBuild.SurfaceSlot<>(surface, BINDING.data(0L),
                        new MeshBuild.CoveragePolicy.Opaque())
                        .coverage().getClass());
    }

    @Test
    void geometryRequiresAtLeastOneShadingSlot() {
        assertThrows(IllegalArgumentException.class,
                () -> new MeshBuild.Geometry<Instance>(null, null, 0, 3));
    }

    @Test
    void geometryAcceptsAnInvisibleVolumeBoundary() {
        VolumeId<Binding, Instance> volume = new VolumeId<>() { };
        MeshBuild.Geometry<Instance> geometry = new MeshBuild.Geometry<>(null,
                new MeshBuild.VolumeSlot<>(volume, BINDING.data(9L)), 0, 3);

        assertNull(geometry.surface());
        assertEquals(volume, geometry.volume().volume());
        assertEquals(9L, geometry.volume().bindingData().bits());
    }

    private static MeshBuild<Instance> build(MeshBuild.IndexRevision revision) {
        SurfaceId<Binding, Instance> surface = new SurfaceId<>() { };
        return new MeshBuild<>(
                new MeshBuild.Stream(range(0x1000L, 36L), 12), null,
                new MeshBuild.Stream(range(0x2000L, 12L), 4), 3, revision,
                List.of(geometry(surface, 0, 3)));
    }

    private static VulkanDeviceAddressRange range(long address, long byteSize) {
        return new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), byteSize);
    }

    private static MeshBuild.Geometry<Instance> geometry(
            SurfaceId<Binding, Instance> surface, int first, int count) {
        return new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(surface,
                BINDING.data(0L),
                new MeshBuild.CoveragePolicy.Cutout(0.5f)),
                null, first, count);
    }
}
