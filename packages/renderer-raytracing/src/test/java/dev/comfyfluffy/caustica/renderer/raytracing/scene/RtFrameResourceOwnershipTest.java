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
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtFrameResourceOwnershipTest {
    interface Binding { }
    interface Instance { }
    private static final ShaderDataType<Binding> BINDING = ShaderDataType.create("binding");
    private static final ShaderDataType<Instance> INSTANCE = ShaderDataType.create("instance");
    private static final SurfaceId<Binding, Instance> SURFACE = new SurfaceId<>() { };
    private static final VolumeId<Binding, Instance> VOLUME = new VolumeId<>() { };
    private static final EnvironmentId<Binding> ENVIRONMENT = new EnvironmentId<>() { };
    private static final SceneId SCENE = new SceneId() { };

    @Test
    void producerReleasePreservesShaderDataForEveryRetainedFrame() {
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        var channel = directory.openFactory(new ContributionOwner(1));
        var positions = channel.create();
        var indices = channel.create();
        var surface = channel.create();
        var volume = channel.create();
        var instance = channel.create();
        var environment = channel.create();

        MeshBuild.Geometry<Instance> geometry = new MeshBuild.Geometry<>(
                new MeshBuild.SurfaceSlot<>(SURFACE,
                        new ShaderData<>(BINDING, 0x1111, surface.reference()),
                        new MeshBuild.CoveragePolicy.Cutout(0.5f)),
                new MeshBuild.VolumeSlot<>(VOLUME,
                        new ShaderData<>(BINDING, 0x2222, volume.reference())), 0, 3);
        MeshBuild<Instance> build = new MeshBuild<>(
                stream(0x1000, 36, 12, positions.reference()),
                stream(0x2000, 12, 4, indices.reference()), 3,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.STATIC, List.of(geometry));
        RetainedSceneSnapshot.Mesh mesh = new RetainedSceneSnapshot.Mesh(1, build,
                List.of(new RetainedSceneSnapshot.GeometryPrograms(3, 5)), null);
        RetainedSceneSnapshot.Instance placement = new RetainedSceneSnapshot.Instance(2, 2, SCENE, 1,
                GeometryTransform.translation(1, 2, 3), 0xff,
                new ShaderData<>(INSTANCE, 0x3333, instance.reference()), List.of());
        EnvironmentBinding<Binding> environmentBinding = EnvironmentBinding.of(ENVIRONMENT,
                new ShaderData<>(BINDING, 0x4444, environment.reference()));
        List<dev.comfyfluffy.caustica.api.resource.ResourceRef> references = List.of(
                positions.reference(), indices.reference(), surface.reference(), volume.reference(),
                instance.reference(), environment.reference());

        ResourceOwners first = ResourceOwners.capture(references);
        surface.close();
        environment.close();
        ResourceOwners second = ResourceOwners.capture(references);

        var firstInput = RtRetainedSceneBackend.resolveFrameInput(mesh, placement);
        var secondInput = RtRetainedSceneBackend.resolveFrameInput(mesh, placement);
        assertEquals(3, firstInput.mesh().geometries().getFirst().surfaceImplementation());
        assertEquals(0x1111, firstInput.mesh().geometries().getFirst().surfaceBinding());
        assertEquals(firstInput, secondInput);
        assertEquals(5, secondInput.mesh().geometries().getFirst().volumeImplementation());
        assertEquals(0x3333, secondInput.placement().instanceData());
        first.close();
        positions.close();
        indices.close();
        volume.close();
        instance.close();
        second.close();
        directory.awaitRetirements();
        directory.close();
    }

    private static MeshBuild.Stream stream(long address, long bytes, int stride,
                                           dev.comfyfluffy.caustica.api.resource.ResourceRef resource) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(
                new VulkanDeviceAddress(address), bytes), stride, resource);
    }
}
