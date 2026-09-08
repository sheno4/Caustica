package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.PreparedScene;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftEntityShutdownTest {
    @Test
    void abandonedGroupReleasesEveryCapturedInputAfterOneCloseFails() {
        var scene = new PreparedScene();
        var closes = new AtomicInteger();
        var rejected = new IllegalStateException("capture release failed");
        var uploader = new MinecraftEntityUploader() {
            @Override public UploadedEntity upload(MinecraftEntityMesh source) { throw new AssertionError(); }
            @Override public UploadJob prepareUpload(MinecraftEntityMesh source) {
                return new UploadJob() {
                    @Override public UploadedEntity finish() { throw new AssertionError(); }
                    @Override public void close() {
                        if (closes.incrementAndGet() == 1) throw rejected;
                    }
                };
            }
        };
        var geometry = new MinecraftEntityGeometry(scene, scene, new SceneId() {}, uploader, Runnable::run);
        var group = geometry.beginUpdateGroup();
        for (int key = 0; key < 2; key++) {
            geometry.put(new MinecraftEntityGeometry.Key(1, key), revision(1), mesh(),
                    GeometryTransform.translation(0, 0, 0), 255);
        }
        assertSame(rejected, assertThrows(IllegalStateException.class, group::close));
        assertEquals(2, closes.get());
        group.close();
        assertEquals(2, closes.get());
        try (var next = geometry.beginUpdateGroup()) {
            assertThrows(IllegalStateException.class, group::submit);
            next.submit();
        }
        geometry.close();
    }

    @Test
    void rejectedRemovalStillReleasesResidentsQueuedInputsAndUploader() {
        var scene = new PreparedScene();
        var uploads = new ArrayList<Uploaded>();
        var closedInputs = new AtomicInteger();
        var closedUploader = new AtomicInteger();
        var uploader = new MinecraftEntityUploader() {
            @Override public UploadedEntity upload(MinecraftEntityMesh source) { throw new AssertionError(); }
            @Override public UploadJob prepareUpload(MinecraftEntityMesh source) {
                return new UploadJob() {
                    @Override public UploadedEntity finish() {
                        var upload = new Uploaded();
                        uploads.add(upload);
                        return upload;
                    }
                    @Override public void close() { closedInputs.incrementAndGet(); }
                };
            }
            @Override public void close() { closedUploader.incrementAndGet(); }
        };
        var geometry = new MinecraftEntityGeometry(scene, scene, new SceneId() {}, uploader, Runnable::run);
        var first = new MinecraftEntityGeometry.Key(1, 1);
        var second = new MinecraftEntityGeometry.Key(1, 2);
        var transform = GeometryTransform.translation(0, 0, 0);
        geometry.put(first, revision(1), mesh(), transform, 255);
        scene.jobs.getLast().complete();
        geometry.put(second, revision(1), mesh(), transform, 255);
        scene.jobs.getLast().complete();
        geometry.put(first, revision(2), mesh(), transform, 255);
        geometry.put(first, revision(3), mesh(), transform, 255);
        scene.reject = true;

        var failure = assertThrows(CompletionException.class, geometry::close);
        assertEquals("rejected edit", failure.getCause().getMessage());
        assertEquals(4, closedInputs.get());
        assertEquals(1, uploads.get(0).closed);
        assertEquals(1, uploads.get(1).closed);
        assertEquals(0, uploads.get(2).closed);
        assertEquals(1, closedUploader.get());
        scene.jobs.getLast().complete();
        assertEquals(1, uploads.get(2).closed);
        assertThrows(CompletionException.class, geometry::close);
        assertEquals(1, closedUploader.get());

        var drops = scene.edits.stream().flatMap(List::stream)
                .filter(SceneEdit.SetInstance.class::isInstance)
                .map(edit -> new SceneEdit.DropInstance(((SceneEdit.SetInstance<?>) edit).instance()))
                .toList();
        scene.edit(drops);
        scene.jobs.forEach(job -> assertEquals(1, job.releases));
    }

    private static MinecraftEntityGeometry.MeshRevision revision(long value) {
        return new MinecraftEntityGeometry.MeshRevision(1, value, 1);
    }

    private static MinecraftEntityMesh mesh() {
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "entity"), null,
                MinecraftEntityMesh.Program.MATERIAL);
        var triangle = new MinecraftEntityMesh.Triangle(material, MinecraftEntityMesh.Coverage.OPAQUE, 0);
        return new MinecraftEntityMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[] {0, 1, 2}, new float[] {0, 0, 1, 0, 0, 1},
                new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, List.of(triangle), 1);
    }

    private static final class Uploaded implements MinecraftEntityUploader.UploadedEntity {
        int closed;
        @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() {
            var positions = new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(0x1000), 36),
                    12, ResourceOwner.none());
            var indices = new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(0x2000), 12),
                    4, ResourceOwner.none());
            var surface = new MeshBuild.SurfaceSlot<>(new SurfaceId<MinecraftProgramTypes.PrimitiveData,
                    MinecraftProgramTypes.InstanceData>() {}, MinecraftProgramTypes.PRIMITIVE_DATA.data(0),
                    new MeshBuild.CoveragePolicy.Opaque());
            return new MeshBuild<>(positions, indices, 3, new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.REFITTABLE,
                    List.of(new MeshBuild.Geometry<>(surface, null, 0, 3)));
        }
        @Override public ShaderData<MinecraftProgramTypes.InstanceData> instanceData() {
            return MinecraftProgramTypes.INSTANCE_DATA.data(0);
        }
        @Override public void close() { closed++; }
    }
}
