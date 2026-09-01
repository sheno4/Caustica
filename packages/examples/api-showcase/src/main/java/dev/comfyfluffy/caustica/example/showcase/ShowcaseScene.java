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
import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
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
    private ResourceGeneration meshResource;

    ShowcaseScene(ShowcasePrograms.Exports programs, List<LightId> lightIds,
                  SceneId scene, GeometryChannel geometry) {
        this.programs = programs;
        this.lightIds = List.copyOf(lightIds);
        this.scene = scene;
        this.geometry = geometry;
        mesh = geometry.newMesh(ShowcasePrograms.INSTANCE);
        instance = geometry.newInstance();
    }

    /** Publishes one immutable generation containing the combined position and index upload buffer. */
    GeometryPublication publishMesh(VulkanDeviceAddressRange currentPositions,
                                     VulkanDeviceAddressRange indices, ResourceGeneration resource) {
        return publish(resource, reference -> geometry.submitGroup(List.of(
                RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh,
                        meshBuild(currentPositions, indices, 1L, reference)))),
                RetainedBatch.of(List.of(placement(scene, GeometryTransform.translation(0.0, 64.0, 0.0)))))));
    }

    /** Replaces the retained mesh streams while every existing placement remains resident. */
    GeometryPublication replaceMesh(VulkanDeviceAddressRange currentPositions,
                                     VulkanDeviceAddressRange indices, long indexRevision,
                                     ResourceGeneration resource) {
        return publish(resource, reference -> geometry.submit(
                RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh,
                        meshBuild(currentPositions, indices, indexRevision, reference))))));
    }

    /** Moves the existing placement, including between simultaneously resident scenes. */
    GeometryPublication moveInstance(SceneId target, GeometryTransform transform) {
        return geometry.submit(RetainedBatch.of(List.of(placement(target, transform))));
    }

    private MeshBuild<ShowcasePrograms.InstanceData> meshBuild(VulkanDeviceAddressRange currentPositions,
                                                                VulkanDeviceAddressRange indices,
                                                               long indexRevision, ResourceRef resource) {
        var opaque = new MeshBuild.SurfaceSlot<>(programs.opaque(),
                ShowcasePrograms.SURFACE_BINDING.data(0L), new MeshBuild.CoveragePolicy.Opaque());
        var cutout = new MeshBuild.SurfaceSlot<>(programs.cutout(),
                ShowcasePrograms.SURFACE_BINDING.data(1L),
                new MeshBuild.CoveragePolicy.Cutout(0.5f));
        var volume = new MeshBuild.VolumeSlot<>(programs.volume(),
                ShowcasePrograms.VOLUME_BINDING.data(0L));
        return new MeshBuild<>(
                new MeshBuild.Stream(currentPositions, 12, resource),
                new MeshBuild.Stream(indices, 4, resource),
                4, new MeshBuild.IndexRevision(indexRevision),
                List.of(
                        new MeshBuild.Geometry<>(opaque, null, 0, 3),
                        new MeshBuild.Geometry<>(cutout, null, 3, 3),
                        new MeshBuild.Geometry<>(opaque, volume, 6, 3),
                        new MeshBuild.Geometry<>(null, volume, 9, 3)));
    }

    private GeometryChannel.SetInstance<ShowcasePrograms.InstanceData> placement(
            SceneId target, GeometryTransform transform) {
        return new GeometryChannel.SetInstance<>(instance, target, mesh, transform, 0xff,
                ShowcasePrograms.INSTANCE.data(7L),
                new PrimitiveLightMap(List.of(
                        new PrimitiveLightMap.Range(0, 1, lightIds.getFirst()))));
    }

    SceneId identity() {
        return scene;
    }

    void stop() {
        ResourceGeneration previous = meshResource;
        GeometryPublication publication = geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.DropInstance(instance),
                new GeometryChannel.DropMesh<>(mesh))));
        meshResource = null;
        if (previous != null) publication.whenVisible(previous::drop);
    }

    private GeometryPublication publish(ResourceGeneration next,
                                        java.util.function.Function<ResourceRef, GeometryPublication> submit) {
        boolean accepted = false;
        try {
            next.seal();
            GeometryPublication publication = submit.apply(next.reference());
            accepted = true;
            ResourceGeneration previous = meshResource;
            meshResource = next;
            if (previous != null) publication.whenVisible(previous::drop);
            return publication;
        } catch (RuntimeException | Error failure) {
            if (!accepted) next.drop();
            throw failure;
        }
    }
}
