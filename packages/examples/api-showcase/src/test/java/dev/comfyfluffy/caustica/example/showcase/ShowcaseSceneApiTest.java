package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ShowcaseSceneApiTest {
    @Test
    void meshPublicationAcceptsTypedAddressRanges() throws Exception {
        assertNotNull(ShowcaseScene.class.getDeclaredMethod("publishMesh",
                VulkanDeviceAddressRange.class, VulkanDeviceAddressRange.class, ResourceOwner.class));
    }

    @Test
    void meshAndPlacementPublishAsOneGroupWithIndependentRetirement() {
        var exports = exports();
        RecordingGeometry geometry = new RecordingGeometry();
        TestResources resources = new TestResources();
        ShowcaseScene scene = new ShowcaseScene(exports, lights(), new SceneId() { }, geometry);
        AtomicBoolean retired = new AtomicBoolean();

        var publication = scene.publishMesh(range(0x1000, 48), range(0x3000, 48),
                resources.create(() -> retired.set(true)));

        assertSame(geometry.publication, publication);
        org.junit.jupiter.api.Assertions.assertFalse(publication.isVisible());
        geometry.publication.makeVisible();
        assertTrue(publication.isVisible());
        assertEquals(1, geometry.groups.size());
        var group = geometry.groups.getFirst();
        assertEquals(2, group.size());
        assertEquals(1, group.get(0).operations().size());
        assertEquals(1, group.get(1).operations().size());
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                GeometryChannel.SetMesh.class, group.get(0).operations().getFirst());
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                GeometryChannel.SetInstance.class, group.get(1).operations().getFirst());
        var setMesh = (GeometryChannel.SetMesh<?>) group.getFirst().operations().getFirst();
        assertSame(resources.generations.getFirst().reference(), setMesh.build().positions().resource());
        assertSame(resources.generations.getFirst().reference(), setMesh.build().indices().resource());
        var frame = setMesh.build().positions().resource().retain();
        scene.stop();
        assertFalse(retired.get());
        assertFalse(geometry.publication.isVisible());
        frame.close();
        assertTrue(retired.get());
    }

    @Test
    void handedOffProgramsAndLightAreSelectionsWhileGeometryMutationStaysLocal() {
        SurfaceId<ShowcasePrograms.SurfaceBindingData, ShowcasePrograms.InstanceData> opaque =
                new SurfaceId<>() { };
        SurfaceId<ShowcasePrograms.SurfaceBindingData, ShowcasePrograms.InstanceData> cutout =
                new SurfaceId<>() { };
        VolumeId<ShowcasePrograms.VolumeBindingData, ShowcasePrograms.InstanceData> volume =
                new VolumeId<>() { };
        EnvironmentId<ShowcasePrograms.EnvironmentBindingData> environment = new EnvironmentId<>() { };
        var exports = new ShowcasePrograms.Exports(
                opaque, cutout, volume, environment, environment, environment);
        SceneId primary = new SceneId() { };
        SceneId alternate = new SceneId() { };
        RecordingGeometry primaryGeometry = new RecordingGeometry();
        RecordingGeometry alternateGeometry = new RecordingGeometry();
        List<LightId> sharedLights = lights();

        ShowcaseScene first = new ShowcaseScene(exports, sharedLights, primary, primaryGeometry);
        ShowcaseScene second = new ShowcaseScene(exports, sharedLights, alternate, alternateGeometry);

        first.publishMesh(range(0x1000, 48), range(0x3000, 48), TestResource.create(() -> { }));
        second.publishMesh(range(0x4000, 48), range(0x6000, 48), TestResource.create(() -> { }));
        var firstPlacement = (GeometryChannel.SetInstance<?>) primaryGeometry.operations.getFirst().get(1);
        var secondPlacement = (GeometryChannel.SetInstance<?>) alternateGeometry.operations.getFirst().get(1);
        assertSame(sharedLights.getFirst(), firstPlacement.primitiveLights().ranges().getFirst().light());
        assertSame(sharedLights.getFirst(), secondPlacement.primitiveLights().ranges().getFirst().light());
        assertSame(primary, firstPlacement.scene());
        assertSame(alternate, secondPlacement.scene());

        first.stop();
        second.stop();

        assertEquals(2, primaryGeometry.operations.size());
        assertEquals(2, alternateGeometry.operations.size());
    }

    @Test
    void retainedMeshCanBeReplacedAndItsPlacementMovedAcrossResidentScenes() {
        RecordingGeometry geometry = new RecordingGeometry();
        SceneId primary = new SceneId() { };
        SceneId portalDestination = new SceneId() { };
        ShowcaseScene scene = new ShowcaseScene(exports(), lights(), primary, geometry);
        AtomicBoolean originalRetired = new AtomicBoolean();
        AtomicBoolean replacementRetired = new AtomicBoolean();

        var original = TestResource.create(() -> originalRetired.set(true));
        scene.publishMesh(range(0x1000, 48), range(0x3000, 48), original);
        var oldFrame = original.retain();
        scene.replaceMesh(range(0x4000, 48), range(0x6000, 48), 2L,
                TestResource.create(() -> replacementRetired.set(true)));
        scene.moveInstance(portalDestination, GeometryTransform.translation(4.0, 70.0, -3.0));

        var replacement = assertInstanceOf(GeometryChannel.SetMesh.class,
                geometry.operations.get(1).getFirst());
        assertEquals(2L, replacement.build().indexRevision().value());
        var moved = assertInstanceOf(GeometryChannel.SetInstance.class,
                geometry.operations.get(2).getFirst());
        assertSame(portalDestination, moved.scene());
        assertFalse(originalRetired.get());
        assertFalse(replacementRetired.get());

        assertFalse(geometry.publications.get(1).isVisible());
        oldFrame.close();
        assertTrue(originalRetired.get());
        assertFalse(replacementRetired.get());
        scene.stop();
        assertFalse(geometry.publication.isVisible());
        assertTrue(replacementRetired.get());
    }

    @Test
    void rejectedReplacementDropsOnlyItsNewGeneration() {
        RecordingGeometry geometry = new RecordingGeometry();
        ShowcaseScene scene = new ShowcaseScene(exports(), lights(), new SceneId() { }, geometry);
        AtomicBoolean currentRetired = new AtomicBoolean();
        AtomicBoolean rejectedRetired = new AtomicBoolean();
        scene.publishMesh(range(0x1000, 48), range(0x3000, 48),
                TestResource.create(() -> currentRetired.set(true)));

        geometry.rejectNext = true;
        assertThrows(IllegalStateException.class, () -> scene.replaceMesh(
                range(0x4000, 48), range(0x6000, 48), 2L,
                TestResource.create(() -> rejectedRetired.set(true))));

        assertTrue(rejectedRetired.get());
        assertFalse(currentRetired.get());
        scene.stop();
        assertTrue(currentRetired.get());
    }

    private static ShowcasePrograms.Exports exports() {
        SurfaceId<ShowcasePrograms.SurfaceBindingData, ShowcasePrograms.InstanceData> opaque =
                new SurfaceId<>() { };
        SurfaceId<ShowcasePrograms.SurfaceBindingData, ShowcasePrograms.InstanceData> cutout =
                new SurfaceId<>() { };
        VolumeId<ShowcasePrograms.VolumeBindingData, ShowcasePrograms.InstanceData> volume =
                new VolumeId<>() { };
        EnvironmentId<ShowcasePrograms.EnvironmentBindingData> environment = new EnvironmentId<>() { };
        return new ShowcasePrograms.Exports(opaque, cutout, volume, environment, environment, environment);
    }

    private static VulkanDeviceAddressRange range(long address, long bytes) {
        return new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), bytes);
    }

    private static List<LightId> lights() {
        return List.of(new LightId() { }, new LightId() { }, new LightId() { });
    }

    private static final class RecordingGeometry implements GeometryChannel {
        private final List<List<Operation>> operations = new ArrayList<>();
        private final List<RetainedBatch<Operation>> batches = new ArrayList<>();
        private final List<List<RetainedBatch<Operation>>> groups = new ArrayList<>();
        private final List<TestPublication> publications = new ArrayList<>();
        private TestPublication publication;
        private boolean rejectNext;

        @Override public <N> MeshId<N> newMesh(ShaderDataType<N> instanceDataType) {
            return new MeshId<>() { };
        }

        @Override public InstanceId newInstance() {
            return new InstanceId() { };
        }

        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submit(
                RetainedBatch<Operation> batch) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("rejected");
            }
            batches.add(batch);
            operations.add(batch.operations());
            publication = new TestPublication();
            publications.add(publication);
            return publication;
        }

        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submitGroup(
                List<RetainedBatch<Operation>> batches) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("rejected");
            }
            groups.add(List.copyOf(batches));
            operations.add(batches.stream().flatMap(batch -> batch.operations().stream()).toList());
            publication = new TestPublication();
            publications.add(publication);
            return publication;
        }
        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submitWithLights(
                List<RetainedBatch<Operation>> geometryBatches,
                dev.comfyfluffy.caustica.api.light.LightChannel lights,
                RetainedBatch<dev.comfyfluffy.caustica.api.light.LightChannel.Operation> lightBatch) {
            throw new UnsupportedOperationException();
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
            callbacks.forEach(Runnable::run);
            callbacks.clear();
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

}
