package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ShowcaseSceneApiTest {
    @Test
    void meshPublicationAcceptsTypedAddressRanges() throws Exception {
        assertNotNull(ShowcaseScene.class.getDeclaredMethod("publishMesh",
                VulkanDeviceAddressRange.class, VulkanDeviceAddressRange.class,
                VulkanDeviceAddressRange.class, Runnable.class));
    }

    @Test
    void meshAndPlacementPublishAsOneGroupWithIndependentRetirement() {
        var exports = exports();
        RecordingGeometry geometry = new RecordingGeometry();
        ShowcaseScene scene = new ShowcaseScene(exports, new SceneId() { }, geometry, new RecordingLights());
        AtomicBoolean retired = new AtomicBoolean();

        scene.publishMesh(range(0x1000, 48), range(0x2000, 48), range(0x3000, 48),
                () -> retired.set(true));

        assertEquals(1, geometry.groups.size());
        var group = geometry.groups.getFirst();
        assertEquals(2, group.size());
        assertEquals(1, group.get(0).operations().size());
        assertEquals(1, group.get(1).operations().size());
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                GeometryChannel.SetMesh.class, group.get(0).operations().getFirst());
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                GeometryChannel.SetInstance.class, group.get(1).operations().getFirst());
        group.getFirst().retired().run();
        assertTrue(retired.get());
    }

    @Test
    void handedOffProgramsDriveTwoIsolatedSceneContributionsThroughStop() {
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
        RecordingLights primaryLights = new RecordingLights();
        RecordingLights alternateLights = new RecordingLights();

        ShowcaseScene first = new ShowcaseScene(exports, primary, primaryGeometry, primaryLights);
        ShowcaseScene second = new ShowcaseScene(exports, alternate, alternateGeometry, alternateLights);

        assertEquals(3, primaryLights.operations.getFirst().size());
        assertEquals(3, alternateLights.operations.getFirst().size());
        primaryLights.operations.getFirst().stream().map(LightChannel.SetLight.class::cast)
                .forEach(light -> assertSame(primary, light.scene()));
        alternateLights.operations.getFirst().stream().map(LightChannel.SetLight.class::cast)
                .forEach(light -> assertSame(alternate, light.scene()));

        first.stop();
        second.stop();

        assertEquals(1, primaryGeometry.operations.size());
        assertEquals(1, alternateGeometry.operations.size());
        assertEquals(2, primaryLights.operations.size());
        assertEquals(2, alternateLights.operations.size());
        assertEquals(3, primaryLights.operations.getLast().size());
        assertEquals(3, alternateLights.operations.getLast().size());
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

    private static final class RecordingGeometry implements GeometryChannel {
        private final List<List<Operation>> operations = new ArrayList<>();
        private final List<List<RetainedBatch<Operation>>> groups = new ArrayList<>();

        @Override public <N> MeshId<N> newMesh(ShaderDataType<N> instanceDataType) {
            return new MeshId<>() { };
        }

        @Override public InstanceId newInstance() {
            return new InstanceId() { };
        }

        @Override public dev.comfyfluffy.caustica.api.geometry.GeometryPublication submit(
                RetainedBatch<Operation> batch) {
            operations.add(batch.operations());
            return dev.comfyfluffy.caustica.api.geometry.GeometryPublication.alreadyVisible();
        }

        @Override public dev.comfyfluffy.caustica.api.geometry.GeometryPublication submitGroup(
                List<RetainedBatch<Operation>> batches) {
            groups.add(List.copyOf(batches));
            operations.add(batches.stream().flatMap(batch -> batch.operations().stream()).toList());
            return dev.comfyfluffy.caustica.api.geometry.GeometryPublication.alreadyVisible();
        }
    }

    private static final class RecordingLights implements LightChannel {
        private final List<List<Operation>> operations = new ArrayList<>();

        @Override public LightId newLight() {
            return new LightId() { };
        }

        @Override public void submit(RetainedBatch<Operation> batch) {
            operations.add(batch.operations());
        }
    }
}
