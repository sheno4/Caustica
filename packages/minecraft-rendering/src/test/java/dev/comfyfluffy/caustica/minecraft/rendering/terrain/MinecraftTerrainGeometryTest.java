package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainEmitter;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightBatch;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftTerrainGeometryTest {
    @Test
    void placementAndNeeLightShareFreshIdentityUntilThePlacementRetires() {
        var geometry = new RecordingChannel();
        var lights = new RecordingLights();
        var terrain = new MinecraftTerrainGeometry(
                geometry, lights, new SceneId() { }, ignored -> new Uploaded(0x9000L));

        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(
                7L, 0, 0, 0, mesh(), emitterBatch(7L, 1L))));

        var firstPlacement = assertInstanceOf(GeometryChannel.SetInstance.class,
                geometry.batches.getFirst().operations().get(1));
        LightId firstMapped = firstPlacement.primitiveLights().ranges().getFirst().light();
        var firstLight = assertInstanceOf(LightChannel.SetLight.class,
                geometry.lightBatches.getFirst().operations().getFirst());
        assertSame(firstMapped, firstLight.light());
        assertTrue(lights.batches.isEmpty());
        assertEquals(0, firstPlacement.primitiveLights().ranges().getFirst().firstPrimitive());
        assertEquals(1, firstPlacement.primitiveLights().ranges().getFirst().primitiveCount());

        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(
                7L, 0, 0, 0, mesh(), emitterBatch(7L, 2L))));
        var secondPlacement = assertInstanceOf(GeometryChannel.SetInstance.class,
                geometry.batches.get(1).operations().get(1));
        LightId secondMapped = secondPlacement.primitiveLights().ranges().getFirst().light();
        assertNotSame(firstMapped, secondMapped);
        var replacementLights = geometry.lightBatches.get(1).operations();
        var retiredLight = assertInstanceOf(LightChannel.DropLight.class, replacementLights.getFirst());
        assertSame(firstMapped, retiredLight.light());
        assertSame(secondMapped, assertInstanceOf(LightChannel.SetLight.class,
                replacementLights.get(1)).light());
    }

    @Test
    void replacementIsOneAtomicMeshAndPlacementBatchAndKeepsTheCurrentUpload() {
        var channel = new RecordingChannel();
        var upload = new Uploaded(0x1000L);
        var terrain = new MinecraftTerrainGeometry(channel, new RecordingLights(), new SceneId() { }, ignored -> upload);

        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(7L, 16, -32, 48, mesh())));

        assertEquals(1, channel.batches.size());
        var batch = channel.batches.getFirst();
        assertInstanceOf(GeometryChannel.SetMesh.class, batch.operations().get(0));
        var placement = assertInstanceOf(GeometryChannel.SetInstance.class, batch.operations().get(1));
        assertEquals(16.0, placement.transform().translationX());
        assertEquals(-32.0, placement.transform().translationY());
        assertEquals(48.0, placement.transform().translationZ());
        assertFalse(upload.closed);
        channel.publication.makeVisible();
        assertFalse(upload.closed);
    }

    @Test
    void acceptedReplacementReleasesDisplacedUploadBeforeVisibility() {
        var channel = new RecordingChannel();
        var first = new Uploaded(0x1010L);
        var second = new Uploaded(0x1020L);
        var uploads = new java.util.ArrayDeque<>(List.of(first, second));
        var terrain = new MinecraftTerrainGeometry(channel, new RecordingLights(), new SceneId() { },
                ignored -> uploads.removeFirst());

        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(7L, 0, 0, 0, mesh())));
        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(7L, 0, 0, 0, mesh())));

        assertFalse(channel.publication.isVisible());
        assertTrue(first.closed);
        assertFalse(second.closed);
        terrain.submit(List.of(new MinecraftTerrainGeometry.Drop(7L)));
        assertTrue(second.closed);
    }

    @Test
    void transactionCoalescesASectionAndDropsMeshAndPlacementTogether() {
        var channel = new RecordingChannel();
        var terrain = new MinecraftTerrainGeometry(channel, new RecordingLights(), new SceneId() { }, ignored -> new Uploaded(0x2000L));
        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(2L, 0, 0, 0, mesh())));

        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(2L, 0, 0, 0, mesh()),
                new MinecraftTerrainGeometry.Drop(2L)));

        var operations = channel.batches.getLast().operations();
        assertEquals(2, operations.size());
        assertInstanceOf(GeometryChannel.DropInstance.class, operations.get(0));
        assertInstanceOf(GeometryChannel.DropMesh.class, operations.get(1));
    }

    @Test
    void groupsTransactionsIntoOnePublicationWithCurrentUploadOwnership() {
        var channel = new RecordingChannel();
        var first = new Uploaded(0x2100L);
        var second = new Uploaded(0x2200L);
        var uploads = new java.util.ArrayDeque<>(List.of(first, second));
        var terrain = new MinecraftTerrainGeometry(channel, new RecordingLights(), new SceneId() { }, ignored -> uploads.removeFirst());

        terrain.submitGroup(List.of(
                List.of(new MinecraftTerrainGeometry.Put(2L, 0, 0, 0, mesh())),
                List.of(new MinecraftTerrainGeometry.Put(3L, 16, 0, 0, mesh()))));

        assertEquals(1, channel.groups.size());
        assertEquals(2, channel.groups.getFirst().size());
        channel.publication.makeVisible();
        assertFalse(first.closed);
        assertFalse(second.closed);
    }

    @Test
    void returnsTheGroupedNativePublicationReceipt() {
        var channel = new RecordingChannel();
        var terrain = new MinecraftTerrainGeometry(channel, new RecordingLights(), new SceneId() { }, ignored -> new Uploaded(0x2250L));

        var publication = terrain.submitGroup(List.of(
                List.of(new MinecraftTerrainGeometry.Put(2L, 0, 0, 0, mesh()))));

        assertSame(channel.publication, publication);
        assertFalse(publication.isVisible());
        channel.publication.makeVisible();
        assertTrue(publication.isVisible());
    }

    @Test
    void acceptedSectionStateTracksSubmissionBeforeNativeVisibility() {
        var channel = new RecordingChannel();
        var terrain = new MinecraftTerrainGeometry(channel, new RecordingLights(), new SceneId() { }, ignored -> new Uploaded(0x2260L));

        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(7L, 0, 0, 0, mesh())));
        assertTrue(terrain.hasSection(7L));
        assertEquals(List.of(7L), terrain.sectionKeys());

        terrain.submit(List.of(new MinecraftTerrainGeometry.Drop(7L)));
        assertFalse(terrain.hasSection(7L));
        assertTrue(terrain.sectionKeys().isEmpty());
    }

    @Test
    void rejectedPublicationGroupReleasesEveryUploadAndKeepsSectionStateRetryable() {
        var channel = new RecordingChannel();
        var first = new Uploaded(0x2300L);
        var second = new Uploaded(0x2400L);
        var uploads = new java.util.ArrayDeque<>(List.of(first, second));
        var terrain = new MinecraftTerrainGeometry(channel, new RecordingLights(), new SceneId() { }, ignored -> uploads.removeFirst());
        channel.rejectNext = true;

        assertThrows(IllegalArgumentException.class, () -> terrain.submitGroup(List.of(
                List.of(new MinecraftTerrainGeometry.Put(2L, 0, 0, 0, mesh())),
                List.of(new MinecraftTerrainGeometry.Put(3L, 16, 0, 0, mesh())))));

        assertTrue(first.closed);
        assertTrue(second.closed);
        terrain.close();
        assertTrue(channel.batches.isEmpty());
    }

    @Test
    void malformedCpuGeometryIsRejectedBeforeUpload() {
        assertThrows(IllegalArgumentException.class, () -> new MinecraftTerrainMesh(
                new float[]{0, 0, 0}, new int[]{0, 0, 0}, new float[0],
                new float[MinecraftTerrainMesh.PRIMITIVE_FLOATS],
                List.of(new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                        MinecraftTerrainMesh.Coverage.OPAQUE, 0, 3, 0.5f)), 1L));
    }

    @Test
    void rejectedCloseKeepsSectionStateSoCloseCanBeRetried() {
        var channel = new RecordingChannel();
        var uploader = new RecordingUploader();
        var terrain = new MinecraftTerrainGeometry(channel, new RecordingLights(), new SceneId() { }, uploader);
        terrain.submit(List.of(new MinecraftTerrainGeometry.Put(9L, 0, 0, 0, mesh())));
        channel.rejectNext = true;

        assertThrows(IllegalArgumentException.class, terrain::close);
        assertEquals(0, uploader.closeCount);
        terrain.close();

        assertEquals(2, channel.batches.size());
        assertEquals(2, channel.batches.getLast().operations().size());
        assertEquals(1, uploader.closeCount);
    }

    @Test
    void primitiveUploadCarriesUvTintEmissionAndAnOutwardTangentBasis() {
        ByteBuffer bytes = ByteBuffer.allocate(MinecraftPrimitiveData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        float[] primitive = new float[MinecraftTerrainMesh.PRIMITIVE_FLOATS];
        primitive[3] = 0.75f;
        primitive[2] = 1f;
        primitive[4] = 0.25f;
        primitive[5] = 0.5f;
        primitive[6] = 1f;
        primitive[8] = 19f;
        primitive[MinecraftTerrainMesh.PRIMITIVE_ATLAS_PRESENT_OFFSET] = 1f;

        MinecraftVulkanTerrainUploader.writePrimitiveRecords(bytes, 1,
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                new float[]{0, 1, 1, 1, 0, 0}, primitive, 37, 41);

        assertEquals(MinecraftPrimitiveData.BYTE_SIZE, bytes.position());
        assertEquals(1f, bytes.getFloat(8));
        assertEquals(0.25f, bytes.getFloat(80));
        assertEquals(0.5f, bytes.getFloat(84));
        assertEquals(1f, bytes.getFloat(88));
        assertEquals(19, bytes.getInt(92));
        assertEquals(37, bytes.getInt(96));
        assertEquals(41, bytes.getInt(100));
        assertEquals(1, bytes.getInt(104));
        assertEquals(0.75f, bytes.getFloat(108));
        assertEquals(1f, bytes.getFloat(112));
        assertEquals(0f, bytes.getFloat(116));
        assertEquals(0f, bytes.getFloat(120));
        assertEquals(0f, bytes.getFloat(128));
        assertEquals(1f, bytes.getFloat(132));
        assertEquals(0f, bytes.getFloat(136));
        assertEquals(2L * MinecraftPrimitiveData.BYTE_SIZE,
                MinecraftVulkanTerrainUploader.primitiveRecordOffset(6));
    }

    @Test
    void untexturedTerrainPrimitiveDoesNotReadTheAtlasDescriptor() {
        ByteBuffer bytes = ByteBuffer.allocate(MinecraftPrimitiveData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        MinecraftVulkanTerrainUploader.writePrimitiveRecords(bytes, 1,
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                new float[]{0, 0, 1, 0, 0, 1}, new float[MinecraftTerrainMesh.PRIMITIVE_FLOATS], 37, 41);

        assertEquals(0, bytes.getInt(96));
        assertEquals(0, bytes.getInt(100));
        assertEquals(0, bytes.getInt(104));
    }

    @Test
    void sharedAtlasDefersExactOnceCleanupUntilOwnerAndEveryUploadRetire() {
        AtomicInteger cleanups = new AtomicInteger();
        var atlasValue = new MinecraftVulkanTerrainUploader.SharedAtlas(37, 41, () -> {
            cleanups.incrementAndGet();
            throw new IllegalStateException("cleanup failure must not escape retirement");
        });
        var atlas = SharedResource.owned(atlasValue, MinecraftVulkanTerrainUploader.SharedAtlas::cleanup);
        var first = atlas.retain();
        var second = atlas.retain();

        atlas.close();
        first.close();
        first.close();
        assertEquals(0, cleanups.get());

        assertDoesNotThrow(second::close);
        second.close();
        assertEquals(1, cleanups.get());
        assertThrows(IllegalStateException.class, atlas::retain);
    }

    @Test
    void uploaderPreservesEveryProgramRangeAsOneNativeGeometry() {
        var first = new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                MinecraftTerrainMesh.Coverage.OPAQUE, 0, 3, 0.5f);
        var second = new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.WATER,
                MinecraftTerrainMesh.Coverage.OPAQUE, 3, 3, 0.5f);
        var source = new MinecraftTerrainMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2, 0, 2, 1}, new float[12],
                new float[2 * MinecraftTerrainMesh.PRIMITIVE_FLOATS], List.of(first, second), 0L);
        var materialSurface = new dev.comfyfluffy.caustica.api.program.SurfaceId<
                MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { };
        var waterSurface = new dev.comfyfluffy.caustica.api.program.SurfaceId<
                MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { };
        var portalSurface = new dev.comfyfluffy.caustica.api.program.SurfaceId<
                MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { };
        var waterVolume = new dev.comfyfluffy.caustica.api.program.VolumeId<
                MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { };
        var environment = new dev.comfyfluffy.caustica.api.program.EnvironmentId<
                MinecraftProgramTypes.EnvironmentBindingData>() { };
        var programs = new MinecraftPrograms(materialSurface, waterSurface, portalSurface, waterVolume, environment);
        var releases = new java.util.concurrent.atomic.AtomicInteger();
        try (var owner = dev.comfyfluffy.caustica.minecraft.rendering.TestResource.create(releases::incrementAndGet)) {
            var bindingResource = owner.reference();

            var geometries = MinecraftVulkanTerrainUploader.geometries(
                    source, programs, new VulkanDeviceAddress(0x4000L), bindingResource);

            assertEquals(2, geometries.size());
            assertSame(materialSurface, geometries.get(0).surface().surface());
            assertEquals(0x4000L, geometries.get(0).surface().bindingData().bits());
            assertSame(waterSurface, geometries.get(1).surface().surface());
            assertSame(waterVolume, geometries.get(1).volume().volume());
            assertSame(bindingResource, geometries.get(0).surface().bindingData().resource());
            assertSame(bindingResource, geometries.get(1).surface().bindingData().resource());
            assertSame(bindingResource, geometries.get(1).volume().bindingData().resource());
            assertEquals(0x4000L + MinecraftPrimitiveData.BYTE_SIZE,
                    geometries.get(1).surface().bindingData().bits());
            assertEquals(3, geometries.get(1).firstIndex());
            assertEquals(3, geometries.get(1).indexCount());
            try (var frame = geometries.get(1).volume().bindingData().resource().retain()) {
                owner.close();
                assertEquals(0, releases.get());
            }
        }
        assertEquals(1, releases.get());
    }

    @Test
    void stochasticTerrainCoverageReachesTheMeshApi() {
        var geometry = new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                MinecraftTerrainMesh.Coverage.STOCHASTIC, 0, 3, 0.4f);

        var policy = assertInstanceOf(MeshBuild.CoveragePolicy.Stochastic.class,
                MinecraftVulkanTerrainUploader.coveragePolicy(geometry));

        assertEquals(0.4f, policy.guideAlphaCutoff());
    }

    private static MinecraftTerrainMesh mesh() {
        return new MinecraftTerrainMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                new float[6], new float[MinecraftTerrainMesh.PRIMITIVE_FLOATS],
                List.of(new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                        MinecraftTerrainMesh.Coverage.OPAQUE, 0, 3, 0.5f)), 3L);
    }

    private static MinecraftTerrainLightBatch emitterBatch(long sectionKey, long revision) {
        var descriptor = new LightDescriptor.Parallelogram(
                0, 0, 0, 0.5, 0, 0, 0, 0.5, 0, 4, 3, 2);
        return new MinecraftTerrainLightBatch(sectionKey, revision,
                List.of(new MinecraftTerrainEmitter(descriptor, 0, 1)));
    }

    private static final class Uploaded implements MinecraftTerrainUploader.UploadedSection {
        private final long address;
        private boolean closed;

        private Uploaded(long address) { this.address = address; }

        @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() {
            var positions = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), 36), 12,
                    ResourceRef.none());
            var indices = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address + 0x100), 12), 4,
                    ResourceRef.none());
            var surface = new MeshBuild.SurfaceSlot<>(new dev.comfyfluffy.caustica.api.program.SurfaceId<
                    MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { },
                    MinecraftProgramTypes.PRIMITIVE_DATA.data(address + 0x200),
                    new MeshBuild.CoveragePolicy.Opaque());
            return new MeshBuild<>(positions, indices, 3, new MeshBuild.IndexRevision(3),
                    List.of(new MeshBuild.Geometry<>(surface, null, 0, 3)));
        }

        @Override public dev.comfyfluffy.caustica.api.program.ShaderData<
                MinecraftProgramTypes.InstanceData> instanceData() {
            return MinecraftProgramTypes.INSTANCE_DATA.data(address + 0x300);
        }

        @Override public void close() { closed = true; }
    }

    private static final class RecordingUploader implements MinecraftTerrainUploader {
        private int closeCount;

        @Override public UploadedSection upload(MinecraftTerrainMesh source) {
            return new Uploaded(0x3000L);
        }

        @Override public void close() {
            closeCount++;
        }
    }

    private static final class RecordingChannel implements GeometryChannel {
        private final List<RetainedBatch<Operation>> batches = new ArrayList<>();
        private final List<List<RetainedBatch<Operation>>> groups = new ArrayList<>();
        private final List<RetainedBatch<LightChannel.Operation>> lightBatches = new ArrayList<>();
        private boolean rejectNext;
        private final TestPublication publication = new TestPublication();

        @Override public <N> MeshId<N> newMesh(ShaderDataType<N> instanceDataType) { return new MeshId<>() { }; }
        @Override public InstanceId newInstance() { return new InstanceId() { }; }
        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submit(
                RetainedBatch<Operation> batch) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalArgumentException("rejected");
            }
            batches.add(batch);
            return publication;
        }
        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submitGroup(
                List<RetainedBatch<Operation>> group) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalArgumentException("rejected");
            }
            groups.add(List.copyOf(group));
            batches.addAll(group);
            return publication;
        }
        @Override public dev.comfyfluffy.caustica.api.retained.RetainedPublication submitWithLights(
                List<RetainedBatch<Operation>> group, LightChannel lights,
                RetainedBatch<LightChannel.Operation> lightBatch) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalArgumentException("rejected");
            }
            groups.add(List.copyOf(group));
            batches.addAll(group);
            lightBatches.add(lightBatch);
            return publication;
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

    private static final class RecordingLights implements LightChannel {
        private final List<RetainedBatch<Operation>> batches = new ArrayList<>();

        @Override public LightId newLight() { return new LightId() { }; }

        @Override public void submit(RetainedBatch<Operation> batch) { batches.add(batch); }
    }
}
