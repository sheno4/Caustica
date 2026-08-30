package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryPublication;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.geometry.PrimitiveLightMap;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.List;

/**
 * Geometry contribution consuming program and light selections from another contribution in the same
 * render session. This owner has mutation authority only over its own mesh and instance ids.
 */
final class ShowcaseScene {
    private final ShowcasePrograms.Exports programs;
    private final GeometryChannel geometry;
    private final SceneId scene;
    private final MeshId<ShowcasePrograms.InstanceData> mesh;
    private final InstanceId instance;
    private final List<LightId> lightIds;

    ShowcaseScene(ShowcasePrograms.Exports programs, List<LightId> lightIds,
                  SceneId scene, GeometryChannel geometry) {
        this.programs = programs;
        this.lightIds = List.copyOf(lightIds);
        this.scene = scene;
        this.geometry = geometry;
        mesh = geometry.newMesh(ShowcasePrograms.INSTANCE);
        instance = geometry.newInstance();
    }

    /**
     * Publishes GPU data uploaded by a world-resource pass. The caller owns all streams until retired runs.
     */
    GeometryPublication publishMesh(VulkanDeviceAddressRange currentPositions,
                                    VulkanDeviceAddressRange previousPositions,
                                    VulkanDeviceAddressRange indices, Runnable retired) {
        var opaque = new MeshBuild.SurfaceSlot<>(programs.opaque(),
                ShowcasePrograms.SURFACE_BINDING.data(0L), new MeshBuild.CoveragePolicy.Opaque());
        var cutout = new MeshBuild.SurfaceSlot<>(programs.cutout(),
                ShowcasePrograms.SURFACE_BINDING.data(1L),
                new MeshBuild.CoveragePolicy.Cutout(0.5f,
                        new MeshBuild.OpacityMicromapHint(0.05f, 0.95f, 2)));
        var volume = new MeshBuild.VolumeSlot<>(programs.volume(),
                ShowcasePrograms.VOLUME_BINDING.data(0L));
        var build = new MeshBuild<>(
                new MeshBuild.Stream(currentPositions, 12),
                new MeshBuild.Stream(previousPositions, 12),
                new MeshBuild.Stream(indices, 4),
                4, new MeshBuild.IndexRevision(1L),
                List.of(
                        new MeshBuild.Geometry<>(opaque, null, 0, 3),
                        new MeshBuild.Geometry<>(cutout, null, 3, 3),
                        new MeshBuild.Geometry<>(opaque, volume, 6, 3),
                        new MeshBuild.Geometry<>(null, volume, 9, 3)));
        return geometry.submitGroup(List.of(
                new RetainedBatch<>(List.of(new GeometryChannel.SetMesh<>(mesh, build)), retired),
                RetainedBatch.of(List.of(new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        GeometryTransform.translation(0.0, 64.0, 0.0), 0xff,
                        ShowcasePrograms.INSTANCE.data(7L),
                        new PrimitiveLightMap(List.of(
                                new PrimitiveLightMap.Range(0, 1, lightIds.getFirst()))))))));
    }

    SceneId identity() {
        return scene;
    }

    void stop() {
        geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.DropInstance(instance),
                new GeometryChannel.DropMesh<>(mesh))));
    }
}
