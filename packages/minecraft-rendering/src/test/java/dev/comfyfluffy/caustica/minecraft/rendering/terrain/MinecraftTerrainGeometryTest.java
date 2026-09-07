package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainEmitter;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightBatch;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;
import dev.comfyfluffy.caustica.minecraft.rendering.PreparedScene;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;

import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftTerrainGeometryTest {
    @Test void waitsForBothNeighborsAndPublishesLightsInTheSameEdit() {
        var scene = new PreparedScene();
        var terrain = new MinecraftTerrainGeometry(scene, scene, new SceneId() {}, ignored -> new Uploaded(0x1000));
        var lights = emitterBatch(7, 1);
        var first = terrain.prepare(new MinecraftTerrainGeometry.Put(7, 16, -32, 48, mesh(), lights));
        var second = terrain.prepare(new MinecraftTerrainGeometry.Put(8, 32, 0, 0, mesh()));
        scene.jobs.get(1).complete();
        assertFalse(first.isDone());
        assertTrue(scene.edits.isEmpty());
        scene.jobs.get(0).complete();
        assertEquals(7L, first.join().sectionKey());
        assertEquals(8L, second.join().sectionKey());
        terrain.edit(List.of(first.join(), second.join()));
        assertEquals(1, scene.edits.size());
        var placement = (SceneEdit.SetInstance<?>) scene.edits.getFirst().stream()
                .filter(SceneEdit.SetInstance.class::isInstance).findFirst().orElseThrow();
        var light = (SceneEdit.SetLight) scene.edits.getFirst().getFirst();
        assertEquals(GeometryTransform.translation(16, -32, 48), placement.transform());
        assertSame(lights.emitters().getFirst().descriptor(), light.descriptor());
        assertSame(light.light(), placement.primitiveLights().ranges().getFirst().light());
        assertEquals(0, placement.primitiveLights().ranges().getFirst().firstPrimitive());
        assertEquals(1, placement.primitiveLights().ranges().getFirst().primitiveCount());
        assertTrue(terrain.hasSection(7));
        terrain.close();
        assertEquals(1, scene.jobs.get(0).releases);
        assertEquals(1, scene.jobs.get(1).releases);
    }

    @Test void rejectionKeepsOldSectionAndPreparedReplacementOwnedByCaller() {
        var scene = new PreparedScene();
        var first = new Uploaded(0x1000);
        var second = new Uploaded(0x2000);
        var uploads = new java.util.ArrayDeque<>(List.of(first, second));
        var terrain = new MinecraftTerrainGeometry(scene, scene, new SceneId() {}, ignored -> uploads.remove());
        var initial = terrain.prepare(new MinecraftTerrainGeometry.Put(7, 0, 0, 0, mesh()));
        scene.jobs.get(0).complete();
        terrain.edit(List.of(initial.join()));
        var replacement = terrain.prepare(new MinecraftTerrainGeometry.Put(7, 0, 0, 0, mesh()));
        assertFalse(first.closed);
        scene.jobs.get(1).complete();
        scene.reject = true;
        assertThrows(IllegalStateException.class, () -> terrain.edit(List.of(replacement.join())));
        assertFalse(first.closed);
        assertFalse(second.closed);
        terrain.edit(List.of(replacement.join()));
        assertTrue(first.closed);
        assertEquals(1, scene.jobs.get(0).releases);
        terrain.close();
        assertTrue(second.closed);
    }

    @Test void failedPreparationReleasesUploadWithoutEditingScene() {
        var scene = new PreparedScene();
        var upload = new Uploaded(0x1000);
        var terrain = new MinecraftTerrainGeometry(scene, scene, new SceneId() {}, ignored -> upload);
        var future = terrain.prepare(new MinecraftTerrainGeometry.Put(7, 0, 0, 0, mesh()));
        scene.jobs.getFirst().future.completeExceptionally(new IllegalStateException("failed build"));
        assertTrue(future.isCompletedExceptionally());
        assertTrue(upload.closed);
        assertTrue(scene.edits.isEmpty());
        terrain.close();
    }

    @Test void repeatedReplacementsCommitInLastOccurrenceOrderAndRejectionKeepsEveryOwner() {
        var scene = new PreparedScene();
        var terrain = new MinecraftTerrainGeometry(scene, scene, new SceneId() {}, ignored -> new Uploaded(0x1000));
        var first = prepared(terrain, scene, 1);
        var second = prepared(terrain, scene, 2);
        var untouched = prepared(terrain, scene, 3);
        terrain.edit(List.of(first, second, untouched));
        var intermediate = prepared(terrain, scene, 1);
        var replacementSecond = prepared(terrain, scene, 2);
        var replacementFirst = prepared(terrain, scene, 1);
        var changes = List.of(intermediate, replacementSecond, replacementFirst);

        scene.reject = true;
        assertThrows(IllegalStateException.class, () -> terrain.edit(changes));
        assertEquals(List.of(1L, 2L, 3L), terrain.sectionKeys());
        assertEquals(1, scene.edits.size());
        scene.jobs.forEach(job -> assertEquals(0, job.releases));

        terrain.edit(changes);
        assertEquals(List.of(3L, 2L, 1L), terrain.sectionKeys());
        assertEquals(2, scene.edits.size());
        var originalInstance = ((SceneEdit.SetInstance<?>) scene.edits.getFirst().getFirst()).instance();
        var updates = scene.edits.getLast();
        assertSame(originalInstance, ((SceneEdit.SetInstance<?>) updates.getFirst()).instance());
        assertSame(originalInstance, ((SceneEdit.SetInstance<?>) updates.getLast()).instance());
        assertEquals(List.of(1, 1, 0, 1, 0, 0), scene.jobs.stream().map(job -> job.releases).toList());
        terrain.close();
        scene.jobs.forEach(job -> assertEquals(1, job.releases));
    }

    @Test void repeatedDropDoesNotRestoreResidentEntryBeforeReinsertion() {
        var scene = new PreparedScene();
        var terrain = new MinecraftTerrainGeometry(scene, scene, new SceneId() {}, ignored -> new Uploaded(0x1000));
        terrain.edit(List.of(prepared(terrain, scene, 1), prepared(terrain, scene, 2)));
        var originalInstance = ((SceneEdit.SetInstance<?>) scene.edits.getFirst().getFirst()).instance();
        var replacement = prepared(terrain, scene, 1);
        terrain.edit(List.of(new MinecraftTerrainGeometry.Drop(1), new MinecraftTerrainGeometry.Drop(1), replacement));
        assertEquals(List.of(2L, 1L), terrain.sectionKeys());
        assertEquals(2, scene.edits.size());
        var updates = scene.edits.getLast();
        assertEquals(2, updates.size());
        assertSame(originalInstance, ((SceneEdit.DropInstance) updates.getFirst()).instance());
        assertNotSame(originalInstance, ((SceneEdit.SetInstance<?>) updates.getLast()).instance());
        assertEquals(1, scene.jobs.getFirst().releases);
        assertEquals(0, scene.jobs.getLast().releases);
        terrain.close();
        scene.jobs.forEach(job -> assertEquals(1, job.releases));
    }

    private static MinecraftTerrainGeometry.Prepared prepared(MinecraftTerrainGeometry terrain,
                                                               PreparedScene scene, long key) {
        var future = terrain.prepare(new MinecraftTerrainGeometry.Put(key, 0, 0, 0, mesh()));
        scene.jobs.getLast().complete();
        return future.join();
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
            var bindingResource = owner;

            var geometries = MinecraftVulkanTerrainUploader.geometries(
                    source, programs, new VulkanDeviceAddress(0x4000L), bindingResource);

            assertEquals(2, geometries.size());
            assertSame(materialSurface, geometries.get(0).surface().surface());
            assertEquals(0x4000L, geometries.get(0).surface().bindingData().bits());
            assertSame(waterSurface, geometries.get(1).surface().surface());
            assertSame(waterVolume, geometries.get(1).volume().volume());
            assertNotSame(bindingResource, geometries.get(0).surface().bindingData().resource());
            assertNotSame(bindingResource, geometries.get(1).surface().bindingData().resource());
            assertNotSame(bindingResource, geometries.get(1).volume().bindingData().resource());
            assertEquals(0x4000L + MinecraftPrimitiveData.BYTE_SIZE,
                    geometries.get(1).surface().bindingData().bits());
            assertEquals(3, geometries.get(1).firstIndex());
            assertEquals(3, geometries.get(1).indexCount());
            try (var frame = geometries.get(1).volume().bindingData().resource().retain()) {
                for (var geometry : geometries) {
                    geometry.surface().bindingData().close();
                    if (geometry.volume() != null) geometry.volume().bindingData().close();
                }
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
                    ResourceOwner.none());
            var indices = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address + 0x100), 12), 4,
                    ResourceOwner.none());
            var surface = new MeshBuild.SurfaceSlot<>(new dev.comfyfluffy.caustica.api.program.SurfaceId<
                    MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { },
                    MinecraftProgramTypes.PRIMITIVE_DATA.data(address + 0x200),
                    new MeshBuild.CoveragePolicy.Opaque());
            return new MeshBuild<>(positions, indices, 3, new MeshBuild.IndexRevision(3), MeshBuild.BuildPolicy.STATIC,
                    List.of(new MeshBuild.Geometry<>(surface, null, 0, 3)));
        }

        @Override public dev.comfyfluffy.caustica.api.program.ShaderData<
                MinecraftProgramTypes.InstanceData> instanceData() {
            return MinecraftProgramTypes.INSTANCE_DATA.data(address + 0x300);
        }

        @Override public void close() { closed = true; }
    }

}
