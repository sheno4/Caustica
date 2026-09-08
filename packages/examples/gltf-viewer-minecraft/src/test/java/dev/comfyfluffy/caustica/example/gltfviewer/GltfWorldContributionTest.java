package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;
import dev.comfyfluffy.caustica.api.geometry.MeshPreparer;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfPrimitiveUploader;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfProgramExports;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfScene;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftEnvironmentSelector;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContext;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.settings.ResourceId;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class GltfWorldContributionTest {
    @Test
    void resourceEpochAtomicallyReplacesMeshesAndStopDropsAllOwnedState() {
        GltfScene scene = new GltfScene(List.of(new GltfScene.Primitive(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                1, .5f, .25f, 1, .4f, 0, false, .5f)),
                List.of(new GltfScene.Placement(0, identity())));
        List<String> stopOrder = new ArrayList<>();
        TestScene geometry = new TestScene();
        geometry.onEdit = () -> stopOrder.add("geometry");
        TestResources resources = new TestResources();
        AtomicInteger destroyed = new AtomicInteger();
        FakeUploader uploader = new FakeUploader(destroyed);
        AtomicInteger registrationCloses = new AtomicInteger();
        ProgramRegistration<GltfProgramExports> registration = registration(registrationCloses, stopOrder);
        AtomicInteger loads = new AtomicInteger();
        GltfWorldContribution contribution = new GltfWorldContribution(
                new WorldContext(geometry, resources), registration,
                () -> { loads.incrementAndGet(); return scene; }, uploader, () -> Set.of(new BlockPos(10, 20, 30)),
                () -> Set.of(new BlockPos(-2, 4, 8)));

        contribution.resourcePackChanged(new ResourcePackEpoch(2));

        assertEquals(1, geometry.batches.size());
        assertEquals(2, geometry.builds.size());
        assertEquals(2, count(geometry.last(), SceneEdit.SetInstance.class));
        SceneEdit.SetInstance<?> authored = geometry.last().stream()
                .filter(SceneEdit.SetInstance.class::isInstance)
                .map(SceneEdit.SetInstance.class::cast).findFirst().orElseThrow();
        assertEquals(10.0, authored.transform().translationX());
        assertEquals(20.0, authored.transform().translationY());
        assertEquals(30.0, authored.transform().translationZ());
        var authoredMesh = geometry.builds.getFirst();
        assertSame(resources.generations.get(0), authoredMesh.positions().resource());
        assertSame(resources.generations.get(1), authoredMesh.indices().resource());
        assertNotSame(resources.generations.get(2),
                authoredMesh.geometries().getFirst().surface().bindingData().resource());

        var oldFrame = geometry.readyMeshes.stream().map(mesh -> mesh.retain()).toList();
        contribution.resourcePackChanged(new ResourcePackEpoch(3));
        assertEquals(2, loads.get());
        assertEquals(2, count(geometry.last(), SceneEdit.DropInstance.class));
        assertEquals(4, geometry.builds.size());
        assertEquals(0, destroyed.get());
        oldFrame.forEach(ResourceOwner::close);
        assertEquals(6, destroyed.get());

        stopOrder.clear();
        contribution.stop();
        assertEquals(2, count(geometry.last(), SceneEdit.DropInstance.class));
        assertEquals(1, registrationCloses.get());
        assertEquals(List.of("geometry", "program"), stopOrder);
        assertEquals(12, destroyed.get());
        contribution.close();
    }

    @Test
    void supersededPreparationWaitsForEveryMeshAndReleasesItsClaims() {
        var geometry = new TestScene();
        geometry.delayed = true;
        var resources = new TestResources();
        var destroyed = new AtomicInteger();
        var contribution = contribution(geometry, resources, destroyed);
        contribution.resourcePackChanged(new ResourcePackEpoch(2));
        contribution.resourcePackChanged(new ResourcePackEpoch(3));
        assertEquals(4, geometry.completions.size());
        geometry.completions.get(0).run();
        assertEquals(0, geometry.batches.size());
        geometry.completions.get(1).run();
        assertEquals(6, destroyed.get());
        assertEquals(0, geometry.batches.size());
        geometry.completions.get(2).run();
        assertEquals(0, geometry.batches.size());
        geometry.completions.get(3).run();
        assertEquals(1, geometry.batches.size());
        assertEquals(2, count(geometry.last(), SceneEdit.SetInstance.class));
        contribution.stop();
        contribution.close();
        assertEquals(12, destroyed.get());
    }

    @Test
    void preparationCompletingAfterStopDoesNotRestoreSceneInstances() {
        var geometry = new TestScene();
        geometry.delayed = true;
        var resources = new TestResources();
        var destroyed = new AtomicInteger();
        var contribution = contribution(geometry, resources, destroyed);
        contribution.resourcePackChanged(new ResourcePackEpoch(2));
        contribution.stop();
        assertEquals(0, destroyed.get());
        geometry.complete();
        assertEquals(1, geometry.batches.size());
        assertEquals(0, count(geometry.last(), SceneEdit.SetInstance.class));
        assertEquals(6, destroyed.get());
        contribution.close();
    }

    private static GltfWorldContribution contribution(
            TestScene geometry, TestResources resources, AtomicInteger destroyed) {
        var authored = new GltfScene(List.of(new GltfScene.Primitive(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                1, .5f, .25f, 1, .4f, 0, false, .5f)),
                List.of(new GltfScene.Placement(0, identity())));
        return new GltfWorldContribution(new WorldContext(geometry, resources),
                registration(new AtomicInteger(), new ArrayList<>()),
                () -> authored, new FakeUploader(destroyed),
                () -> Set.of(new BlockPos(10, 20, 30)), () -> Set.of(new BlockPos(-2, 4, 8)));
    }

    private static ProgramRegistration<GltfProgramExports> registration(
            AtomicInteger closes, List<String> stopOrder) {
        GltfProgramExports exports = new GltfProgramExports(new SurfaceId<>() { }, new SurfaceId<>() { });
        return new ProgramRegistration<>() {
            @Override public GltfProgramExports exports() { return exports; }
            @Override public void whenComplete(
                    java.util.function.Consumer<? super Completion> callback) { }
            @Override public void close() { closes.incrementAndGet(); stopOrder.add("program"); }
        };
    }

    private static long count(List<SceneEdit> batch, Class<?> type) {
        return batch.stream().filter(type::isInstance).count();
    }

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static final class FakeUploader implements GltfPrimitiveUploader {
        private final AtomicInteger destroyed;
        private long address = 0x1000;
        private FakeUploader(AtomicInteger destroyed) { this.destroyed = destroyed; }
        @Override public Uploaded upload(ResourceFactory resources, GltfScene.Primitive primitive) {
            long base = address;
            address += 0x1000;
            ResourceOwner positions = generation(resources);
            ResourceOwner indices = generation(resources);
            ResourceOwner primitiveData = generation(resources);
            return new Uploaded() {
                @Override public MeshBuild.Stream positionsStream() {
                    return stream(base, 36, 12, positions);
                }
                @Override public MeshBuild.Stream indexStream() {
                    return stream(base + 0x100, 12, 4, indices);
                }
                @Override public VulkanDeviceAddress primitiveDataAddress() {
                    return new VulkanDeviceAddress(base + 0x200);
                }
                @Override public ResourceOwner primitiveDataResource() { return primitiveData; }
                @Override public int vertexCount() { return 3; }
                @Override public int indexCount() { return 3; }
                @Override public void close() {
                    primitiveData.close();
                    indices.close();
                    positions.close();
                }
            };
        }
        private ResourceOwner generation(ResourceFactory resources) {
            return resources.create(destroyed::incrementAndGet);
        }
        private static MeshBuild.Stream stream(long address, long size, int stride, ResourceOwner resource) {
            return new MeshBuild.Stream(new VulkanDeviceAddressRange(
                    new VulkanDeviceAddress(address), size), stride, resource);
        }
    }

    private record WorldContext(TestScene geometry, ResourceFactory resources)
            implements MinecraftWorldSessionContext {
        @Override public RenderSessionContext renderSession() {
            return new RenderSessionContext() {
                @Override public GpuDevice gpu() { return null; }
                @Override public GpuComputeQueue compute() { return null; }
                @Override public ProgramChannel program() { return null; }
                @Override public PassChannel passes() { return null; }
                @Override public MeshPreparer meshes() { return geometry; }
                @Override public SceneChannel scene() { return geometry; }
                @Override public ResourceFactory resources() { return resources; }
            };
        }
        @Override public SceneId scene() { return SCENE; }
        @Override public MinecraftDimensionKey dimension() {
            return new MinecraftDimensionKey(ResourceId.of("minecraft", "overworld"));
        }
        @Override public ResourcePackEpoch resourcePackEpoch() { return new ResourcePackEpoch(1); }
        @Override public MinecraftEnvironmentSelector environment() {
            return binding -> { };
        }
    }

    private static final class TestResources implements ResourceFactory {
        private final List<ResourceOwner> generations = new ArrayList<>();
        @Override public ResourceOwner create(Runnable retired) {
            ResourceOwner generation = TestResource.create(retired);
            generations.add(generation);
            return generation;
        }
    }


    private static final SceneId SCENE = new SceneId() { };
}
