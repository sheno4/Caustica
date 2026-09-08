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
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
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
                        new ShaderData<>(BINDING, 0x1111, surface),
                        new MeshBuild.CoveragePolicy.Cutout(0.5f)),
                new MeshBuild.VolumeSlot<>(VOLUME,
                        new ShaderData<>(BINDING, 0x2222, volume)), 0, 3);
        MeshBuild<Instance> build = new MeshBuild<>(
                stream(0x1000, 36, 12, positions),
                stream(0x2000, 12, 4, indices), 3,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.STATIC, List.of(geometry));
        RetainedSceneSnapshot.Mesh mesh = new RetainedSceneSnapshot.Mesh(1, build, null);
        var composition = new ProgramComposition(List.of(), java.util.Map.of(SURFACE, 3, VOLUME, 5));
        RetainedSceneSnapshot.Instance placement = new RetainedSceneSnapshot.Instance(2, 2, SCENE, 1,
                GeometryTransform.translation(1, 2, 3), 0xff,
                new ShaderData<>(INSTANCE, 0x3333, instance), List.of());
        EnvironmentBinding<Binding> environmentBinding = new EnvironmentBinding<>(ENVIRONMENT,
                new ShaderData<>(BINDING, 0x4444, environment));
        List<dev.comfyfluffy.caustica.api.resource.ResourceOwner> references = List.of(
                positions, indices, surface, volume,
                instance, environment);

        ResourceOwners first = ResourceOwners.capture(references);
        surface.close();
        environment.close();
        ResourceOwners second = ResourceOwners.capture(references.stream().map(first::borrowed).toList());

        var snapshot = new RetainedSceneSnapshot(1,
                List.of(new RetainedSceneSnapshot.Scene(SCENE, environmentBinding)),
                List.of(mesh), List.of(placement), List.of());
        var assembly = new RtRetainedSceneBackend.FrameAssembly(
                (value, programs) -> new RtRetainedSceneBackend.FrameMesh(value, null, programs));
        var firstInput = assembly.resolve(snapshot, composition).get(SCENE).getFirst().instances.getFirst();
        var secondInput = assembly.resolve(snapshot, composition).get(SCENE).getFirst().instances.getFirst();
        assertEquals(3, firstInput.geometryRecords().getFirst().surfaceImplementation());
        assertEquals(0x1111, firstInput.geometryRecords().getFirst().surfaceBinding());
        assertEquals(firstInput, secondInput);
        assertEquals(5, secondInput.geometryRecords().getFirst().volumeImplementation());
        assertEquals(0x3333, secondInput.current().instanceData().bits());
        assembly.clear();
        first.close();
        positions.close();
        indices.close();
        volume.close();
        instance.close();
        second.close();
        geometry.surface().bindingData().close();
        geometry.volume().bindingData().close();
        placement.instanceData().close();
        environmentBinding.bindingData().close();
        directory.awaitRetirements();
        directory.close();
    }

    private static MeshBuild.Stream stream(long address, long bytes, int stride,
                                           dev.comfyfluffy.caustica.api.resource.ResourceOwner resource) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(
                new VulkanDeviceAddress(address), bytes), stride, resource);
    }
}
