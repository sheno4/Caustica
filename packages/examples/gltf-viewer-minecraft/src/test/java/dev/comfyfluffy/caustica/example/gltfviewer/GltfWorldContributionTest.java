package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.scene.SceneId;
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
import static org.junit.jupiter.api.Assertions.assertSame;

final class GltfWorldContributionTest {
    @Test
    void resourceEpochAtomicallyReplacesMeshesAndStopDropsAllOwnedState() {
        GltfScene scene = new GltfScene(List.of(new GltfScene.Primitive(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                1, .5f, .25f, 1, .4f, 0, false, .5f)),
                List.of(new GltfScene.Placement(0, identity())));
        GltfViewerAssetRepository assets = new GltfViewerAssetRepository(() -> scene);
        List<String> stopOrder = new ArrayList<>();
        CaptureGeometry geometry = new CaptureGeometry(stopOrder);
        TestResources resources = new TestResources();
        AtomicInteger destroyed = new AtomicInteger();
        FakeUploader uploader = new FakeUploader(destroyed);
        AtomicInteger registrationCloses = new AtomicInteger();
        ProgramRegistration<GltfProgramExports> registration = registration(registrationCloses, stopOrder);
        GltfWorldContribution contribution = new GltfWorldContribution(
                new WorldContext(geometry, resources), registration,
                assets, uploader, () -> Set.of(new BlockPos(10, 20, 30)),
                () -> Set.of(new BlockPos(-2, 4, 8)));

        contribution.resourcePackChanged(new ResourcePackEpoch(2));

        assertEquals(1, geometry.batches.size());
        assertEquals(2, count(geometry.last(), GeometryChannel.SetMesh.class));
        assertEquals(2, count(geometry.last(), GeometryChannel.SetInstance.class));
        GeometryChannel.SetInstance<?> authored = geometry.last().operations().stream()
                .filter(GeometryChannel.SetInstance.class::isInstance)
                .map(GeometryChannel.SetInstance.class::cast).findFirst().orElseThrow();
        assertEquals(10.0, authored.transform().translationX());
        assertEquals(20.0, authored.transform().translationY());
        assertEquals(30.0, authored.transform().translationZ());
        var authoredMesh = (GeometryChannel.SetMesh<?>) geometry.last().operations().stream()
                .filter(GeometryChannel.SetMesh.class::isInstance).findFirst().orElseThrow();
        assertSame(resources.generations.get(0).reference(), authoredMesh.build().positions().resource());
        assertSame(resources.generations.get(1).reference(), authoredMesh.build().indices().resource());
        assertSame(resources.generations.get(2).reference(),
                authoredMesh.build().geometries().getFirst().surface().bindingData().resource());

        contribution.resourcePackChanged(new ResourcePackEpoch(3));
        assertEquals(2, count(geometry.last(), GeometryChannel.DropMesh.class));
        assertEquals(2, count(geometry.last(), GeometryChannel.DropInstance.class));
        assertEquals(2, count(geometry.last(), GeometryChannel.SetMesh.class));
        assertEquals(0, destroyed.get());
        geometry.publications.get(1).makeVisible();
        assertEquals(6, destroyed.get());

        stopOrder.clear();
        contribution.stop();
        assertEquals(2, count(geometry.last(), GeometryChannel.DropMesh.class));
        assertEquals(2, count(geometry.last(), GeometryChannel.DropInstance.class));
        assertEquals(1, registrationCloses.get());
        assertEquals(List.of("geometry", "program"), stopOrder);
        assertEquals(6, destroyed.get());
        geometry.publications.get(2).makeVisible();
        assertEquals(12, destroyed.get());
        contribution.close();
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

    private static long count(RetainedBatch<GeometryChannel.Operation> batch, Class<?> type) {
        return batch.operations().stream().filter(type::isInstance).count();
    }

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static final class CaptureGeometry implements GeometryChannel {
        private final List<RetainedBatch<Operation>> batches = new ArrayList<>();
        private final List<TestPublication> publications = new ArrayList<>();
        private final List<String> order;
        private CaptureGeometry(List<String> order) { this.order = order; }
        @Override public <N> MeshId<N> newMesh(ShaderDataType<N> type) { return new MeshId<>() { }; }
        @Override public InstanceId newInstance() { return new InstanceId() { }; }
        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submit(
                RetainedBatch<Operation> batch) {
            batches.add(batch);
            order.add("geometry");
            TestPublication publication = new TestPublication();
            publications.add(publication);
            return publication;
        }
        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submitGroup(
                List<RetainedBatch<Operation>> accepted) {
            batches.addAll(accepted);
            order.add("geometry");
            TestPublication publication = new TestPublication();
            publications.add(publication);
            return publication;
        }
        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submitWithLights(
                List<RetainedBatch<Operation>> geometryBatches,
                dev.comfyfluffy.caustica.api.light.LightChannel lights,
                RetainedBatch<dev.comfyfluffy.caustica.api.light.LightChannel.Operation> lightBatch) {
            throw new UnsupportedOperationException();
        }
        RetainedBatch<Operation> last() { return batches.getLast(); }
    }

    private static final class FakeUploader implements GltfPrimitiveUploader {
        private final AtomicInteger destroyed;
        private long address = 0x1000;
        private FakeUploader(AtomicInteger destroyed) { this.destroyed = destroyed; }
        @Override public Uploaded upload(ResourceFactory resources, GltfScene.Primitive primitive) {
            long base = address;
            address += 0x1000;
            ResourceGeneration positions = generation(resources);
            ResourceGeneration indices = generation(resources);
            ResourceGeneration primitiveData = generation(resources);
            return new Uploaded() {
                @Override public MeshBuild.Stream positionsStream() {
                    return stream(base, 36, 12, positions.reference());
                }
                @Override public MeshBuild.Stream indexStream() {
                    return stream(base + 0x100, 12, 4, indices.reference());
                }
                @Override public VulkanDeviceAddress primitiveDataAddress() {
                    return new VulkanDeviceAddress(base + 0x200);
                }
                @Override public ResourceRef primitiveDataResource() { return primitiveData.reference(); }
                @Override public int vertexCount() { return 3; }
                @Override public int indexCount() { return 3; }
                @Override public void drop() {
                    primitiveData.drop();
                    indices.drop();
                    positions.drop();
                }
            };
        }
        private ResourceGeneration generation(ResourceFactory resources) {
            ResourceGeneration generation = resources.create(destroyed::incrementAndGet);
            generation.seal();
            return generation;
        }
        private static MeshBuild.Stream stream(long address, long size, int stride, ResourceRef resource) {
            return new MeshBuild.Stream(new VulkanDeviceAddressRange(
                    new VulkanDeviceAddress(address), size), stride, resource);
        }
    }

    private record WorldContext(CaptureGeometry geometry, ResourceFactory resources)
            implements MinecraftWorldSessionContext {
        @Override public RenderSessionContext renderSession() {
            return new RenderSessionContext() {
                @Override public GpuDevice gpu() { return null; }
                @Override public ProgramChannel program() { return null; }
                @Override public PassChannel passes() { return null; }
                @Override public GeometryChannel geometry() { return geometry; }
                @Override public LightChannel lights() { return null; }
                @Override public ResourceFactory resources() { return resources; }
            };
        }
        @Override public SceneId scene() { return SCENE; }
        @Override public MinecraftDimensionKey dimension() {
            return new MinecraftDimensionKey(ResourceId.of("minecraft", "overworld"));
        }
        @Override public ResourcePackEpoch resourcePackEpoch() { return new ResourcePackEpoch(1); }
        @Override public MinecraftEnvironmentSelector environment() {
            return binding -> dev.comfyfluffy.caustica.api.retained.RetainedPublication.alreadyVisible();
        }
    }

    private static final class TestPublication
            implements dev.comfyfluffy.caustica.api.retained.RetainedPublication {
        private final List<Runnable> callbacks = new ArrayList<>();
        private boolean visible;
        @Override public boolean isVisible() { return visible; }
        @Override public void whenVisible(Runnable callback) {
            if (visible) callback.run();
            else callbacks.add(callback);
        }
        void makeVisible() {
            visible = true;
            List.copyOf(callbacks).forEach(Runnable::run);
            callbacks.clear();
        }
    }

    private static final class TestResources implements ResourceFactory {
        private final List<TestGeneration> generations = new ArrayList<>();
        @Override public ResourceGeneration create(Runnable retired) {
            TestGeneration generation = new TestGeneration(retired);
            generations.add(generation);
            return generation;
        }
    }

    private static final class TestGeneration implements ResourceGeneration {
        private final ResourceRef reference = new ResourceRef() { };
        private final Runnable retired;
        private boolean dropped;
        private TestGeneration(Runnable retired) { this.retired = retired; }
        @Override public ResourceRef reference() { return reference; }
        @Override public void seal() { }
        @Override public void drop() {
            if (dropped) return;
            dropped = true;
            retired.run();
        }
    }

    private static final SceneId SCENE = new SceneId() { };
}
