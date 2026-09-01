package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtFrameResourceFallbackTest {
    interface Binding { }
    interface Instance { }
    private static final ShaderDataType<Binding> BINDING = ShaderDataType.create("binding");
    private static final ShaderDataType<Instance> INSTANCE = ShaderDataType.create("instance");
    private static final SurfaceId<Binding, Instance> SURFACE = new SurfaceId<>() { };
    private static final VolumeId<Binding, Instance> VOLUME = new VolumeId<>() { };
    private static final EnvironmentId<Binding> ENVIRONMENT = new EnvironmentId<>() { };
    private static final SceneId SCENE = new SceneId() { };

    @Test
    void droppedDataChangesOnlyCapturesTakenAfterTheDrop() {
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        var channel = directory.openChannel(new ContributionOwner(1));
        var positions = sealed(channel.create());
        var indices = sealed(channel.create());
        var surface = sealed(channel.create());
        var volume = sealed(channel.create());
        var instance = sealed(channel.create());
        var environment = sealed(channel.create());

        MeshBuild.Geometry<Instance> geometry = new MeshBuild.Geometry<>(
                new MeshBuild.SurfaceSlot<>(SURFACE,
                        new ShaderData<>(BINDING, 0x1111, surface.reference()),
                        new MeshBuild.CoveragePolicy.Cutout(0.5f)),
                new MeshBuild.VolumeSlot<>(VOLUME,
                        new ShaderData<>(BINDING, 0x2222, volume.reference())), 0, 3);
        MeshBuild<Instance> build = new MeshBuild<>(
                stream(0x1000, 36, 12, positions.reference()),
                stream(0x2000, 12, 4, indices.reference()), 3,
                new MeshBuild.IndexRevision(1), List.of(geometry));
        RetainedSceneSnapshot.Mesh mesh = new RetainedSceneSnapshot.Mesh(1, build,
                List.of(new RetainedSceneSnapshot.GeometryPrograms(3, 5)));
        RetainedSceneSnapshot.Instance placement = new RetainedSceneSnapshot.Instance(2, SCENE, 1,
                GeometryTransform.translation(1, 2, 3), 0xff,
                new ShaderData<>(INSTANCE, 0x3333, instance.reference()), List.of());
        EnvironmentBinding<Binding> environmentBinding = EnvironmentBinding.of(ENVIRONMENT,
                new ShaderData<>(BINDING, 0x4444, environment.reference()));
        List<dev.comfyfluffy.caustica.api.resource.ResourceRef> references = List.of(
                positions.reference(), indices.reference(), surface.reference(), volume.reference(),
                instance.reference(), environment.reference());

        ResourceLeaseSet first = ResourceLeaseSet.capture(references);
        surface.drop();
        environment.drop();
        ResourceLeaseSet second = ResourceLeaseSet.capture(references);

        var firstInput = RtRetainedSceneBackend.resolveFrameInput(mesh, placement, first).orElseThrow();
        var secondInput = RtRetainedSceneBackend.resolveFrameInput(mesh, placement, second).orElseThrow();
        assertEquals(3, firstInput.mesh().geometries().getFirst().surfaceImplementation());
        assertEquals(0x1111, firstInput.mesh().geometries().getFirst().surfaceBinding());
        assertEquals(0, secondInput.mesh().geometries().getFirst().surfaceImplementation());
        assertEquals(0, secondInput.mesh().geometries().getFirst().surfaceBinding());
        assertEquals(5, secondInput.mesh().geometries().getFirst().volumeImplementation());
        assertNotNull(RtRetainedSceneBackend.resolveFrameEnvironment(environmentBinding, first));
        assertNull(RtRetainedSceneBackend.resolveFrameEnvironment(environmentBinding, second));

        instance.drop();
        ResourceLeaseSet third = ResourceLeaseSet.capture(references);
        var instanceFallback = RtRetainedSceneBackend.resolveFrameInput(mesh, placement, third).orElseThrow();
        var fallbackGeometry = instanceFallback.mesh().geometries().getFirst();
        assertEquals(0, fallbackGeometry.surfaceImplementation());
        assertEquals(0, fallbackGeometry.volumeImplementation());
        assertEquals(0, fallbackGeometry.surfaceBinding());
        assertEquals(0, fallbackGeometry.volumeBinding());
        assertEquals(0, instanceFallback.placement().instanceData());

        positions.drop();
        ResourceLeaseSet fourth = ResourceLeaseSet.capture(references);
        assertTrue(RtRetainedSceneBackend.resolveFrameInput(mesh, placement, first).isPresent(),
                "the already captured frame keeps its position generation");
        assertTrue(RtRetainedSceneBackend.resolveFrameInput(mesh, placement, fourth).isEmpty(),
                "a new frame omits a mesh whose current positions are unavailable");

        first.close();
        second.close();
        third.close();
        fourth.close();
    }

    private static dev.comfyfluffy.caustica.api.resource.ResourceGeneration sealed(
            dev.comfyfluffy.caustica.api.resource.ResourceGeneration generation) {
        generation.seal();
        return generation;
    }

    private static MeshBuild.Stream stream(long address, long bytes, int stride,
                                           dev.comfyfluffy.caustica.api.resource.ResourceRef resource) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(
                new VulkanDeviceAddress(address), bytes), stride, resource);
    }
}
