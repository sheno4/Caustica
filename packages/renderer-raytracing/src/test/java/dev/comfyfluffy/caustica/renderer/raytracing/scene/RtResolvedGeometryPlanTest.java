package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtResolvedGeometryPlanTest {
    interface Binding { }
    interface Instance { }

    private static final ShaderDataType<Binding> BINDING = ShaderDataType.create("binding");
    private static final SurfaceId<Binding, Instance> SURFACE = new SurfaceId<>() { };
    private static final VolumeId<Binding, Instance> VOLUME = new VolumeId<>() { };

    @Test
    void unavailableInstanceDataFallsBackWithoutChangingTraversalShape() {
        MeshBuild.Geometry<Instance> geometry = new MeshBuild.Geometry<>(
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(0x1111),
                        new MeshBuild.CoveragePolicy.Cutout(0.4f)),
                new MeshBuild.VolumeSlot<>(VOLUME, BINDING.data(0x2222)), 0, 3);
        MeshBuild<Instance> build = new MeshBuild<>(stream(0x1000, 36, 12),
                stream(0x2000, 12, 4), 3, new MeshBuild.IndexRevision(1), List.of(geometry));
        var resolved = new RtRetainedGeometryPlan.ResolvedMesh(build, List.of(
                new RtRetainedGeometryPlan.ResolvedGeometry(geometry, 0, 0, 0, 0)));
        GeometryTransform transform = GeometryTransform.translation(1, 2, 3);

        var record = RtRetainedGeometryPlan.records(resolved,
                new RtRetainedGeometryPlan.ResolvedPlacement(transform, 0), transform,
                build.positions()).getFirst();

        assertEquals(0, record.surfaceImplementation());
        assertEquals(0, record.coverageImplementation());
        assertEquals(0, record.volumeImplementation());
        assertEquals(0, record.surfaceBinding());
        assertEquals(0, record.volumeBinding());
        assertEquals(0, record.instanceData());
        assertTrue((record.flags() & RtRetainedGeometryPlan.HAS_SURFACE) != 0);
        assertTrue((record.flags() & RtRetainedGeometryPlan.HAS_VOLUME) != 0);
        assertTrue((record.flags() & RtRetainedGeometryPlan.CUTOUT) != 0);
    }

    private static MeshBuild.Stream stream(long address, long bytes, int stride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(
                new VulkanDeviceAddress(address), bytes), stride);
    }
}
