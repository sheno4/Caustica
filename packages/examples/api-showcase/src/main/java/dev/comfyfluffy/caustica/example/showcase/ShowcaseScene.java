package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Prepares replacement geometry while the current placement remains available to frames. */
final class ShowcaseScene {
    private final ShowcasePrograms.Exports programs;
    private final SceneChannel edits;
    private final MeshPreparer meshes;
    private final SceneId scene;
    private final InstanceId instance;
    private final List<LightId> lightIds;
    private ReadyMesh<ShowcasePrograms.InstanceData> mesh;
    private SceneId target;
    private GeometryTransform transform = GeometryTransform.translation(0.0, 64.0, 0.0);
    private long request;
    private boolean stopped;

    ShowcaseScene(ShowcasePrograms.Exports programs, List<LightId> lightIds,
                  SceneId scene, SceneChannel edits, MeshPreparer meshes) {
        this.programs = programs;
        this.lightIds = List.copyOf(lightIds);
        this.scene = scene;
        this.target = scene;
        this.edits = edits;
        this.meshes = meshes;
        instance = edits.newInstance();
    }

    CompletableFuture<Void> publishMesh(VulkanDeviceAddressRange positions,
            VulkanDeviceAddressRange indices, ResourceOwner resource) {
        return replaceMesh(positions, indices, 1L, resource);
    }

    synchronized CompletableFuture<Void> replaceMesh(VulkanDeviceAddressRange positions,
            VulkanDeviceAddressRange indices, long indexRevision, ResourceOwner resource) {
        long preparing = ++request;
        CompletableFuture<ReadyMesh<ShowcasePrograms.InstanceData>> prepared;
        try {
            prepared = meshes.prepare(ShowcasePrograms.INSTANCE,
                    meshBuild(positions, indices, indexRevision, resource.reference()));
        } finally {
            resource.close();
        }
        return prepared.thenAccept(next -> {
            synchronized (this) {
                if (stopped || preparing != request) { next.close(); return; }
                try { edits.edit(List.of(placement(next))); }
                catch (RuntimeException | Error failure) { next.close(); throw failure; }
                var previous = mesh;
                mesh = next;
                if (previous != null) previous.close();
            }
        });
    }

    synchronized void moveInstance(SceneId target, GeometryTransform transform) {
        if (mesh != null) edits.edit(List.of(new SceneEdit.SetInstance<>(instance, target, mesh,
                transform, 0xff, ShowcasePrograms.INSTANCE.data(7L), primitiveLights())));
        this.target = target;
        this.transform = transform;
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

    private SceneEdit.SetInstance<ShowcasePrograms.InstanceData> placement(
            ReadyMesh<ShowcasePrograms.InstanceData> ready) {
        return new SceneEdit.SetInstance<>(instance, target, ready, transform, 0xff,
                ShowcasePrograms.INSTANCE.data(7L), primitiveLights());
    }

    private PrimitiveLightMap primitiveLights() {
        return new PrimitiveLightMap(List.of(new PrimitiveLightMap.Range(0, 1, lightIds.getFirst())));
    }

    SceneId identity() { return scene; }

    synchronized void stop() {
        if (stopped) return;
        stopped = true;
        request++;
        if (mesh != null) {
            edits.edit(List.of(new SceneEdit.DropInstance(instance)));
            mesh.close();
            mesh = null;
        }
    }
}
