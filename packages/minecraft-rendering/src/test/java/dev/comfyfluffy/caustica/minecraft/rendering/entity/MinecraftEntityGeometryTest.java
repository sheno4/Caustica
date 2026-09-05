package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftEntityGeometryTest {
    @Test
    void updateGroupReturnsTheNativePublicationReceipt() {
        RecordingChannel channel = new RecordingChannel();
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x900L));
        var key = new MinecraftEntityGeometry.Key(2, 1);

        dev.comfyfluffy.caustica.api.retained.RetainedPublication publication;
        try (MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup()) {
            geometry.put(key, revision(1), mesh(), GeometryTransform.translation(1, 2, 3), 0xff);
            publication = updates.submit();
        }

        assertSame(channel.publication, publication);
        assertFalse(publication.isVisible());
        channel.publication.makeVisible();
        assertTrue(publication.isVisible());
    }

    @Test
    void compatibleReplacementKeepsMeshIdentityAndPlacementInOneAtomicBatch() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded first = new Uploaded(0x1000L);
        Uploaded second = new Uploaded(0x2000L);
        var uploads = new ArrayList<>(List.of(first, second));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(2, 7);

        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(10, 20, 30), 0xff);
        geometry.put(key, revision(2), mesh(), GeometryTransform.translation(10, 20, 30), 0xff);

        assertEquals(1, channel.batches.size());
        assertEquals(1, channel.groups.size());
        var initial = channel.batches.get(0).operations();
        var replacement = channel.groups.getFirst().getFirst().operations();
        assertEquals(2, initial.size());
        assertEquals(2, replacement.size());
        var initialMesh = assertInstanceOf(GeometryChannel.SetMesh.class, initial.get(0));
        var initialPlacement = assertInstanceOf(GeometryChannel.SetInstance.class, initial.get(1));
        var replacementMesh = assertInstanceOf(GeometryChannel.SetMesh.class, replacement.get(0));
        var replacementPlacement = assertInstanceOf(GeometryChannel.SetInstance.class, replacement.get(1));
        assertSame(initialMesh.mesh(), replacementMesh.mesh());
        assertSame(initialPlacement.instance(), replacementPlacement.instance());
        assertEquals(GeometryTransform.translation(10, 20, 30), replacementPlacement.transform());
    }

    @Test
    void topologyChangeKeepsTheResidentsLogicalMeshIdentity() {
        RecordingChannel channel = new RecordingChannel();
        var uploads = new ArrayList<>(List.of(new Uploaded(0x2100L), new Uploaded(0x2200L)));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(2, 8);

        geometry.put(key, new MinecraftEntityGeometry.MeshRevision(0, 1, 17), mesh(),
                GeometryTransform.translation(10, 20, 30), 0xff);
        geometry.put(key, new MinecraftEntityGeometry.MeshRevision(0, 2, 18), mesh(),
                GeometryTransform.translation(10, 20, 30), 0xff);

        var initial = channel.batches.get(0).operations();
        var replacement = channel.groups.getFirst().getFirst().operations();
        assertSame(assertInstanceOf(GeometryChannel.SetMesh.class, initial.get(0)).mesh(),
                assertInstanceOf(GeometryChannel.SetMesh.class, replacement.get(0)).mesh());
        assertEquals(2, replacement.size());
    }

    @Test
    void equalMeshRevisionsRemainIsolatedAcrossIndependentPlacements() {
        RecordingChannel channel = new RecordingChannel();
        var uploaded = new ArrayList<>(List.of(new Uploaded(0x2800L), new Uploaded(0x3800L)));
        int[] uploads = {0};
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> {
            uploads[0]++;
            return uploaded.removeFirst();
        });
        var first = new MinecraftEntityGeometry.Key(2, 1);
        var second = new MinecraftEntityGeometry.Key(2, 2);

        geometry.put(first, revision(7), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
        geometry.put(second, revision(7), mesh(), GeometryTransform.translation(2, 0, 0), 0xff);

        assertEquals(2, uploads[0]);
        var firstMesh = assertInstanceOf(GeometryChannel.SetMesh.class,
                channel.batches.get(0).operations().get(0));
        var secondMesh = assertInstanceOf(GeometryChannel.SetMesh.class,
                channel.batches.get(1).operations().get(0));
        assertNotSame(firstMesh.mesh(), secondMesh.mesh());
        assertEquals(2, channel.batches.get(1).operations().size());

        geometry.drop(first);
        assertInstanceOf(GeometryChannel.DropMesh.class, channel.batches.get(2).operations().get(1));
        geometry.drop(second);
        assertInstanceOf(GeometryChannel.DropMesh.class, channel.batches.get(3).operations().get(1));
    }

    @Test
    void unchangedRevisionReusesTheSameResidentsMesh() {
        RecordingChannel channel = new RecordingChannel();
        int[] uploads = {0};
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> {
            uploads[0]++;
            return new Uploaded(0x4800L);
        });
        var key = new MinecraftEntityGeometry.Key(2, 3);

        geometry.put(key, revision(7), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
        geometry.put(key, revision(7), mesh(), GeometryTransform.translation(2, 0, 0), 0xff);

        assertEquals(1, uploads[0]);
        assertEquals(1, channel.batches.size());
        assertEquals(GeometryTransform.translation(2, 0, 0),
                channel.latestGroups.getFirst().getFirst().transform());
    }

    @Test
    void unchangedRevisionAndPlacementDoNotUploadOrPublish() {
        RecordingChannel channel = new RecordingChannel();
        int[] uploads = {0};
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> {
            uploads[0]++;
            return new Uploaded(0x4900L);
        });
        var key = new MinecraftEntityGeometry.Key(2, 4);
        GeometryTransform transform = GeometryTransform.translation(1, 2, 3);

        geometry.put(key, revision(7), mesh(), transform, 0xff);
        geometry.put(key, revision(7), mesh(), transform, 0xff);

        assertEquals(1, uploads[0]);
        assertEquals(1, channel.batches.size());
    }

    @Test
    void unchangedTransformAndMaskDoNotPublish() {
        RecordingChannel channel = new RecordingChannel();
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x4a00L));
        var key = new MinecraftEntityGeometry.Key(2, 5);
        GeometryTransform transform = GeometryTransform.translation(1, 2, 3);
        geometry.put(key, revision(7), mesh(), transform, 0xff);

        geometry.transform(key, transform, 0xff);

        assertEquals(1, channel.batches.size());
    }

    @Test
    void frameUpdatesKeepCurrentUploadsOwnedAfterPublication() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded firstUpload = new Uploaded(0x4b00L);
        Uploaded secondUpload = new Uploaded(0x4c00L);
        var uploads = new ArrayList<>(List.of(firstUpload, secondUpload));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());

        try (MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup()) {
            geometry.put(new MinecraftEntityGeometry.Key(2, 6), revision(1), mesh(),
                    GeometryTransform.translation(1, 0, 0), 0xff);
            geometry.put(new MinecraftEntityGeometry.Key(2, 7), revision(1), mesh(),
                    GeometryTransform.translation(2, 0, 0), 0xff);
            assertTrue(channel.groups.isEmpty());
            updates.submit();
        }

        assertEquals(1, channel.groups.size());
        assertEquals(2, channel.groups.getFirst().size());
        channel.publication.makeVisible();
        assertFalse(firstUpload.closed);
        assertFalse(secondUpload.closed);
    }

    @Test
    void acceptedReplacementReleasesDisplacedUploadBeforeVisibility() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded first = new Uploaded(0x4c10L);
        Uploaded second = new Uploaded(0x4c20L);
        var uploads = new ArrayList<>(List.of(first, second));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(2, 70);

        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
        geometry.put(key, revision(2), mesh(), GeometryTransform.translation(2, 0, 0), 0xff);

        assertFalse(channel.publication.isVisible());
        assertTrue(first.closed);
        assertFalse(second.closed);
    }

    @Test
    void rejectedFrameGroupRestoresResidentsAndClosesUnacceptedUploads() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded rejected = new Uploaded(0x4d00L);
        Uploaded retry = new Uploaded(0x4e00L);
        var uploads = new ArrayList<>(List.of(rejected, retry));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(2, 8);
        channel.rejectNextGroup = true;

        MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup();
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
        assertThrows(IllegalStateException.class, updates::submit);
        assertTrue(rejected.closed);

        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
        assertEquals(1, channel.batches.size());
        assertFalse(retry.closed);
    }

    @Test
    void rejectedCompatibleReplacementKeepsTheStableMeshRetryable() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded initial = new Uploaded(0x4e10L);
        Uploaded rejected = new Uploaded(0x4e20L);
        Uploaded retry = new Uploaded(0x4e30L);
        var uploads = new ArrayList<>(List.of(initial, rejected, retry));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(2, 80);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
        Object stableMesh = assertInstanceOf(GeometryChannel.SetMesh.class,
                channel.batches.getFirst().operations().getFirst()).mesh();
        channel.rejectNextGroup = true;

        MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup();
        geometry.put(key, revision(2), mesh(), GeometryTransform.translation(2, 0, 0), 0xff);
        assertThrows(IllegalStateException.class, updates::submit);
        assertTrue(rejected.closed);

        geometry.put(key, revision(2), mesh(), GeometryTransform.translation(2, 0, 0), 0xff);
        assertSame(stableMesh, assertInstanceOf(GeometryChannel.SetMesh.class,
                channel.batches.getLast().operations().getFirst()).mesh());
        assertFalse(retry.closed);
    }

    @Test
    void rejectedReplacementAndLatestPlacementDoNotCloseAcceptedState() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded initial = new Uploaded(0x4e30L);
        Uploaded rejected = new Uploaded(0x4e40L);
        var uploads = new ArrayList<>(List.of(initial, rejected));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(2, 801);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
        channel.rejectNextGroup = true;

        assertThrows(IllegalStateException.class, () -> geometry.put(
                key, revision(2), mesh(), GeometryTransform.translation(2, 0, 0), 0xff));

        assertFalse(initial.closed);
        assertTrue(rejected.closed);
        assertEquals(1, channel.batches.size());
        assertEquals(0, channel.groups.size());
    }

    @Test
    void rejectedGroupWithRepeatedReplacementClosesIntroducedUploadsAndKeepsPrior() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded prior = new Uploaded(0x4e40L);
        Uploaded first = new Uploaded(0x4e50L);
        Uploaded second = new Uploaded(0x4e60L);
        var uploads = new ArrayList<>(List.of(prior, first, second));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(2, 81);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 0xff);
        channel.rejectNextGroup = true;

        MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup();
        geometry.put(key, revision(2), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
        geometry.put(key, revision(3), mesh(), GeometryTransform.translation(2, 0, 0), 0xff);
        assertThrows(IllegalStateException.class, updates::submit);

        assertEquals(0, prior.closeCount);
        assertEquals(1, first.closeCount);
        assertEquals(1, second.closeCount);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(3, 0, 0), 0xff);
    }

    @Test
    void stagingFailureBeforeGroupAcceptanceClosesTheUntransferredUpload() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded uploaded = new Uploaded(0x4f00L) {
            @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() {
                throw new IllegalStateException("broken build");
            }
        };
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploaded);

        try (MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup()) {
            assertThrows(IllegalStateException.class, () -> geometry.put(
                    new MinecraftEntityGeometry.Key(2, 9), revision(1), mesh(),
                    GeometryTransform.translation(1, 0, 0), 0xff));
            assertTrue(uploaded.closed);
        }
    }

    @Test
    void transformUsesLatestPlacementWithoutQueuingOrChangingTheMeshLifetime() {
        RecordingChannel channel = new RecordingChannel();
        Uploaded uploaded = new Uploaded(0x3000L);
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploaded);
        var key = new MinecraftEntityGeometry.Key(1, 9);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 0xff);

        geometry.transform(key, GeometryTransform.translation(1, 2, 3), 0x01);

        assertEquals(1, channel.batches.size());
        GeometryChannel.LatestInstance operation = channel.latestGroups.getFirst().getFirst();
        assertEquals(GeometryTransform.translation(1, 2, 3), operation.transform());
        assertEquals(0x01, operation.mask());
        channel.publication.makeVisible();
        assertFalse(uploaded.closed);
    }

    @Test
    void everyUnchangedMeshFrameSubmitsItsLatestRigidPlacementWithoutUploading() {
        RecordingChannel channel = new RecordingChannel();
        int[] uploads = {0};
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> {
            uploads[0]++;
            return new Uploaded(0x3100L);
        });
        var key = new MinecraftEntityGeometry.Key(1, 10);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 0xff);

        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(2, 0, 0), 0xff);

        assertEquals(1, uploads[0]);
        assertEquals(1, channel.batches.size());
        assertEquals(2, channel.latestGroups.size());
        assertEquals(GeometryTransform.translation(2, 0, 0),
                channel.latestGroups.getLast().getFirst().transform());
    }

    @Test
    void movementContinuesWhileAChangedMeshPublicationIsInvisible() {
        RecordingChannel channel = new RecordingChannel();
        var uploads = new ArrayList<>(List.of(new Uploaded(0x3200L), new Uploaded(0x3300L)));
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(1, 11);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 0xff);
        assertFalse(channel.publication.isVisible());

        try (MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup()) {
            geometry.put(key, revision(2), mesh(), GeometryTransform.translation(1, 0, 0), 0xff);
            updates.submit();
        }
        try (MinecraftEntityGeometry.UpdateGroup updates = geometry.beginUpdateGroup()) {
            geometry.put(key, revision(2), mesh(), GeometryTransform.translation(2, 0, 0), 0xff);
            updates.submit();
        }

        assertEquals(1, channel.groups.size());
        assertEquals(GeometryTransform.translation(1, 0, 0),
                channel.latestGroups.getFirst().getFirst().transform());
        assertEquals(GeometryTransform.translation(2, 0, 0),
                channel.latestGroups.getLast().getFirst().transform());
    }

    @Test
    void removalDropsPlacementAndMeshTogether() {
        RecordingChannel channel = new RecordingChannel();
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x4000L));
        var key = new MinecraftEntityGeometry.Key(2, 11);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 0xff);

        geometry.drop(key);

        var removal = channel.batches.get(1).operations();
        assertEquals(2, removal.size());
        assertInstanceOf(GeometryChannel.DropInstance.class, removal.get(0));
        assertInstanceOf(GeometryChannel.DropMesh.class, removal.get(1));
    }

    @Test
    void rejectedRemovalLeavesTheResidentRetryable() {
        RecordingChannel channel = new RecordingChannel();
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x5000L));
        var key = new MinecraftEntityGeometry.Key(1, 12);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 0xff);
        channel.rejectNext = true;

        assertThrows(IllegalStateException.class, () -> geometry.drop(key));
        geometry.drop(key);

        assertEquals(2, channel.batches.size());
        assertInstanceOf(GeometryChannel.DropInstance.class, channel.batches.get(1).operations().getFirst());
    }

    @Test
    void rejectedStopDoesNotOrphanLiveResidents() {
        RecordingChannel channel = new RecordingChannel();
        var geometry = new MinecraftEntityGeometry(channel, new SceneId() { }, ignored -> new Uploaded(0x6000L));
        var key = new MinecraftEntityGeometry.Key(1, 13);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 0xff);
        channel.rejectNext = true;

        assertThrows(IllegalStateException.class, geometry::stop);
        geometry.transform(key, GeometryTransform.translation(4, 5, 6), 0xff);
        geometry.stop();

        assertEquals(2, channel.batches.size());
        assertEquals(GeometryTransform.translation(4, 5, 6),
                channel.latestGroups.getFirst().getFirst().transform());
        assertInstanceOf(GeometryChannel.DropInstance.class, channel.batches.get(1).operations().getFirst());
    }

    private static MinecraftEntityMesh mesh() {
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "entity"), null,
                MinecraftEntityMesh.Program.MATERIAL);
        var triangle = new MinecraftEntityMesh.Triangle(material, MinecraftEntityMesh.Coverage.OPAQUE,
                0, 0, 1, 0);
        return new MinecraftEntityMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[] {0, 1, 2}, new float[] {0, 0, 1, 0, 0, 1},
                new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, List.of(triangle), 17);
    }

    private static MinecraftEntityGeometry.MeshRevision revision(long content) {
        return new MinecraftEntityGeometry.MeshRevision(0, content, 17);
    }

    private static class Uploaded implements MinecraftEntityUploader.UploadedEntity {
        final long address;
        boolean closed;
        int closeCount;

        Uploaded(long address) { this.address = address; }

        @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() {
            var positions = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), 36), 12,
                    ResourceRef.none());
            var indices = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address + 0x100), 12), 4,
                    ResourceRef.none());
            var surface = new MeshBuild.SurfaceSlot<>(new SurfaceId<
                    MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { },
                    MinecraftProgramTypes.PRIMITIVE_DATA.data(address + 0x200),
                    new MeshBuild.CoveragePolicy.Opaque());
            return new MeshBuild<>(positions, indices, 3, new MeshBuild.IndexRevision(17),
                    List.of(new MeshBuild.Geometry<>(surface, null, 0, 3)));
        }

        @Override public dev.comfyfluffy.caustica.api.program.ShaderData<
                MinecraftProgramTypes.InstanceData> instanceData() {
            return MinecraftProgramTypes.INSTANCE_DATA.data(address + 0x300);
        }

        @Override public void close() { closed = true; closeCount++; }
    }

    private static final class RecordingChannel implements GeometryChannel {
        final List<RetainedBatch<Operation>> batches = new ArrayList<>();
        final List<List<RetainedBatch<Operation>>> groups = new ArrayList<>();
        final List<List<GeometryChannel.LatestInstance>> latestGroups = new ArrayList<>();
        boolean rejectNext;
        boolean rejectNextGroup;
        final TestPublication publication = new TestPublication();

        @Override public <N> MeshId<N> newMesh(dev.comfyfluffy.caustica.api.program.ShaderDataType<N> type) {
            return new MeshId<>() { };
        }

        @Override public InstanceId newInstance() { return new InstanceId() { }; }

        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submit(
                RetainedBatch<Operation> batch) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("rejected");
            }
            batches.add(batch);
            return publication;
        }

        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submitGroup(
                List<RetainedBatch<Operation>> group) {
            if (rejectNextGroup) {
                rejectNextGroup = false;
                throw new IllegalStateException("rejected group");
            }
            groups.add(List.copyOf(group));
            return publication;
        }
        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submitGroupWithLatest(
                List<RetainedBatch<Operation>> group, List<GeometryChannel.LatestInstance> latest) {
            if (rejectNextGroup) {
                rejectNextGroup = false;
                throw new IllegalStateException("rejected group");
            }
            if (!group.isEmpty()) groups.add(List.copyOf(group));
            latestGroups.add(List.copyOf(latest));
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
}
