package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.geometry.PrimitiveLightMap;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.List;

final class ShowcaseScene {
    private final ShowcasePrograms.Exports programs;
    private final GeometryChannel geometry;
    private final LightChannel lights;
    private final SceneId scene;
    private final MeshId<ShowcasePrograms.InstanceData> mesh;
    private final InstanceId instance;
    private final List<LightId> lightIds;

    ShowcaseScene(ShowcasePrograms.Exports programs, SceneId scene,
                  GeometryChannel geometry, LightChannel lights) {
        this.programs = programs;
        this.scene = scene;
        this.geometry = geometry;
        this.lights = lights;
        mesh = geometry.newMesh(ShowcasePrograms.INSTANCE);
        instance = geometry.newInstance();
        lightIds = List.of(lights.newLight(), lights.newLight(), lights.newLight());
        publishLights();
    }

    /**
     * Publishes GPU data uploaded by a world-resource pass. The caller owns all streams until retired runs.
     */
    void publishMesh(long currentPositionAddress, long previousPositionAddress,
                     long indexAddress, Runnable retired) {
        var opaque = new MeshBuild.SurfaceSlot<>(programs.opaque(),
                ShowcasePrograms.SURFACE_BINDING.data(0L), new MeshBuild.CoveragePolicy.Opaque());
        var cutout = new MeshBuild.SurfaceSlot<>(programs.cutout(),
                ShowcasePrograms.SURFACE_BINDING.data(1L),
                new MeshBuild.CoveragePolicy.Cutout(0.5f,
                        new MeshBuild.OpacityMicromapHint(0.05f, 0.95f, 2)));
        var volume = new MeshBuild.VolumeSlot<>(programs.volume(),
                ShowcasePrograms.VOLUME_BINDING.data(0L));
        var build = new MeshBuild<>(
                stream(currentPositionAddress, 48L, 12),
                stream(previousPositionAddress, 48L, 12),
                stream(indexAddress, 48L, 4),
                4, new MeshBuild.IndexRevision(1L),
                List.of(
                        new MeshBuild.Geometry<>(opaque, null, 0, 3),
                        new MeshBuild.Geometry<>(cutout, null, 3, 3),
                        new MeshBuild.Geometry<>(opaque, volume, 6, 3),
                        new MeshBuild.Geometry<>(null, volume, 9, 3)));
        geometry.submit(new RetainedBatch<>(List.of(
                new GeometryChannel.SetMesh<>(mesh, build),
                new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        GeometryTransform.translation(0.0, 64.0, 0.0), 0xff,
                        ShowcasePrograms.INSTANCE.data(7L),
                        new PrimitiveLightMap(List.of(
                                new PrimitiveLightMap.Range(0, 1, lightIds.getFirst()))))), retired));
    }

    private static MeshBuild.Stream stream(long address, long byteSize, int byteStride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(
                new VulkanDeviceAddress(address), byteSize), byteStride);
    }

    private void publishLights() {
        lights.submit(RetainedBatch.of(List.of(
                new LightChannel.SetLight(lightIds.get(0), scene, new LightDescriptor.Rectangle(
                        0, 66, 0, 0.5, 0, 0, 0, 0, 0.5, 20, 18, 15)),
                new LightChannel.SetLight(lightIds.get(1), scene, new LightDescriptor.Spot(
                        0, 66, 0, 0, -1, 0, 24, 0.35, 500, 450, 400)),
                new LightChannel.SetLight(lightIds.get(2), scene, new LightDescriptor.Distant(
                        0, 1, 0, 100_000, 95_000, 90_000, 0.00465, false)))));
    }

    void stop() {
        geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.DropInstance(instance),
                new GeometryChannel.DropMesh<>(mesh))));
        lights.submit(RetainedBatch.of(lightIds.stream()
                .map(LightChannel.DropLight::new)
                .map(LightChannel.Operation.class::cast)
                .toList()));
    }
}
