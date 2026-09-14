package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.minecraft.rendering.texture.BorrowedMinecraftTexture;
import dev.comfyfluffy.caustica.minecraft.rendering.texture.MinecraftTextureSampler;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialPageCompiler;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftVulkanEntityUploaderTest {
    @Test void captureOwnsOneLeasePerTextureAndCancellationAllocatesNoGpuResources() {
        var caller = Thread.currentThread();
        var resolved = new ArrayList<MinecraftEntityMesh.Texture>();
        var closed = new ArrayList<String>();
        var texture = MinecraftEntityMesh.Texture.standalone(ResourceId.of("test", "entity"));
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "entity"), texture,
                MinecraftEntityMesh.Program.MATERIAL);
        var uploader = uploader(unavailableGpu(method -> fail("capture called GPU " + method)), input -> {
            assertSame(caller, Thread.currentThread());
            resolved.add(input);
            return lease(closed, "texture", false);
        });

        var job = uploader.prepareUpload(mesh(List.of(triangle(material, MinecraftEntityMesh.Coverage.CUTOUT),
                triangle(material, MinecraftEntityMesh.Coverage.CUTOUT))));

        assertEquals(List.of(texture), resolved);
        assertTrue(closed.isEmpty());
        job.close();
        job.close();
        assertEquals(List.of("texture"), closed);
    }

    @Test void failedWorkerAllocationReleasesCapturedLeasesOnce() throws Exception {
        var caller = Thread.currentThread();
        var gpuThreads = new ArrayList<Thread>();
        var closed = new ArrayList<String>();
        var texture = MinecraftEntityMesh.Texture.standalone(ResourceId.of("test", "entity"));
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "entity"), texture,
                MinecraftEntityMesh.Program.MATERIAL);
        var failure = new IllegalStateException("allocation failed");
        var uploader = uploader(unavailableGpu(method -> {
            gpuThreads.add(Thread.currentThread());
            throw failure;
        }), input -> {
            assertSame(caller, Thread.currentThread());
            return lease(closed, "texture", false);
        });
        var job = uploader.prepareUpload(mesh(List.of(triangle(material, MinecraftEntityMesh.Coverage.CUTOUT))));
        assertTrue(gpuThreads.isEmpty());

        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> assertSame(failure, assertThrows(IllegalStateException.class, job::finish))).get();
        }
        assertEquals(1, gpuThreads.size());
        assertNotSame(caller, gpuThreads.getFirst());
        assertEquals(List.of("texture"), closed);
        job.close();
        assertEquals(List.of("texture"), closed);
    }

    @Test void failedHostCaptureClosesEarlierLeases() {
        var closed = new ArrayList<String>();
        var firstTexture = MinecraftEntityMesh.Texture.standalone(ResourceId.of("test", "first"));
        var secondTexture = MinecraftEntityMesh.Texture.standalone(ResourceId.of("test", "second"));
        var first = new MinecraftEntityMesh.Material(ResourceId.of("test", "first"), firstTexture,
                MinecraftEntityMesh.Program.MATERIAL);
        var second = new MinecraftEntityMesh.Material(ResourceId.of("test", "second"), secondTexture,
                MinecraftEntityMesh.Program.MATERIAL);
        var uploader = uploader(unavailableGpu(method -> fail("capture called GPU " + method)), input -> {
            if (input.equals(secondTexture)) throw new IllegalStateException("capture failed");
            return lease(closed, "first", false);
        });

        assertThrows(IllegalStateException.class, () -> uploader.prepareUpload(mesh(List.of(
                triangle(first, MinecraftEntityMesh.Coverage.CUTOUT),
                triangle(second, MinecraftEntityMesh.Coverage.CUTOUT)))));
        assertEquals(List.of("first"), closed);
    }

    private static MinecraftVulkanEntityUploader uploader(GpuDevice gpu, EntityTextureResolver resolver) {
        var materials = MinecraftMaterialLookup.compile(new ResourcePackEpoch(1), List.of(), List.of(),
                MinecraftMaterialPageCompiler.compile(List.of()));
        return new MinecraftVulkanEntityUploader(gpu, materials,
                new MinecraftPrograms(null, null, null, null, null, null), resolver,
                retired -> { throw new AssertionError("resource ownership requires a completed upload"); });
    }

    private static GpuDevice unavailableGpu(Consumer<String> access) {
        return new GpuDevice() {
            @Override public VkDevice vk() { access.accept("vk"); throw new AssertionError(); }
            @Override public long vmaAllocator() { access.accept("vmaAllocator"); throw new AssertionError(); }
            @Override public int[] asyncBufferSharingQueueFamilies() {
                access.accept("asyncBufferSharingQueueFamilies");
                throw new AssertionError();
            }
            @Override public GpuDescriptorHeap descriptorHeap() {
                access.accept("descriptorHeap");
                throw new AssertionError();
            }
        };
    }

    @Test void groupsOnlyAdjacentMatchingProgramAndCoverage() {
        var material = material("stone", MinecraftEntityMesh.Program.MATERIAL);
        var portal = material("portal", MinecraftEntityMesh.Program.PORTAL);
        MinecraftEntityMesh mesh = mesh(List.of(triangle(material, MinecraftEntityMesh.Coverage.OPAQUE),
                triangle(material, MinecraftEntityMesh.Coverage.OPAQUE),
                triangle(material, MinecraftEntityMesh.Coverage.CUTOUT),
                triangle(portal, MinecraftEntityMesh.Coverage.CUTOUT)));

        assertEquals(List.of(new MinecraftVulkanEntityUploader.GeometryRange(0, 2),
                new MinecraftVulkanEntityUploader.GeometryRange(2, 3),
                new MinecraftVulkanEntityUploader.GeometryRange(3, 4)),
                MinecraftVulkanEntityUploader.geometryRanges(mesh));
    }

    @Test void primitivePackingUsesMaterialDescriptorTintAndEmission() {
        var triangle = triangle(material("stone", MinecraftEntityMesh.Program.MATERIAL),
                MinecraftEntityMesh.Coverage.CUTOUT);
        var uv = new MinecraftPrimitiveData.Float2[]{new MinecraftPrimitiveData.Float2(0, 0),
                new MinecraftPrimitiveData.Float2(1, 0), new MinecraftPrimitiveData.Float2(0, 1)};

        MinecraftPrimitiveData record = MinecraftEntityPrimitives.primitiveRecord(
                triangle, uv, 96, 17, 23, 29, MinecraftEntityPrimitives.TangentBasis.ZERO);

        assertEquals(17, record.materialIndex());
        assertEquals(23, record.baseTexture().value());
        assertEquals(29, record.baseSampler().value());
        assertEquals(1, record.textureFlags());
        assertEquals(2.5f, record.primitiveEmission());
        assertEquals(96, record.vertexColorsOffset());
        ByteBuffer bytes = ByteBuffer.allocate(MinecraftPrimitiveData.BYTE_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        record.write(bytes);
        assertEquals(17, bytes.getInt(MinecraftPrimitiveData.MATERIAL_INDEX_OFFSET));
        assertEquals(23, bytes.getInt(MinecraftPrimitiveData.BASE_TEXTURE_OFFSET));
        assertEquals(29, bytes.getInt(MinecraftPrimitiveData.BASE_SAMPLER_OFFSET));
        assertEquals(1, bytes.getInt(MinecraftPrimitiveData.TEXTURE_FLAGS_OFFSET));
        assertEquals(2.5f, bytes.getFloat(MinecraftPrimitiveData.PRIMITIVE_EMISSION_OFFSET));
    }

    @Test void textureSetDestroysDescriptorThenAllLeasesAndAggregatesFailures() {
        ArrayList<String> closed = new ArrayList<>();
        var range = new dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange<
                dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex.Resource>() {
            @Override public dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex.Resource firstIndex() {
                return new dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex.Resource(4);
            }
            @Override public int descriptorCount() { return 1; }
            @Override public void destroy() { closed.add("descriptor"); throw new IllegalStateException("descriptor"); }
        };
        var samplers = new GpuDescriptorRange<GpuDescriptorIndex.Sampler>() {
            @Override public GpuDescriptorIndex.Sampler firstIndex() { return new GpuDescriptorIndex.Sampler(8); }
            @Override public int descriptorCount() { return 1; }
            @Override public void destroy() { closed.add("sampler"); }
        };
        BorrowedMinecraftTexture first = lease(closed, "first", true);
        BorrowedMinecraftTexture second = lease(closed, "second", false);
        var set = new MinecraftVulkanEntityUploader.TextureSet(
                range, samplers, Map.of(), List.of(first, second));

        IllegalStateException failure = assertThrows(IllegalStateException.class, set::close);
        assertEquals(List.of("descriptor", "sampler", "first", "second"), closed);
        assertEquals(1, failure.getSuppressed().length);
        set.close();
        assertEquals(List.of("descriptor", "sampler", "first", "second"), closed);
    }

    private static BorrowedMinecraftTexture lease(
            List<String> closed, String name, boolean fail) {
        return lease(closed, name, fail ? new IllegalArgumentException(name) : null);
    }

    private static BorrowedMinecraftTexture lease(
            List<String> closed, String name, RuntimeException failure) {
        return new BorrowedMinecraftTexture() {
            @Override public long vkImage() { return 1; }
            @Override public int format() { return 37; }
            @Override public int baseMipLevel() { return 0; }
            @Override public int mipLevels() { return 1; }
            @Override public int imageLayout() { return 0; }
            @Override public MinecraftTextureSampler sampler() { return MinecraftTextureSampler.PIXEL_ART; }
            @Override public void close() {
                closed.add(name);
                if (failure != null) throw failure;
            }
        };
    }

    @Test void tangentBasisUsesIndexedPositionAndUvGradients() {
        var positions = java.nio.FloatBuffer.wrap(new float[]{0, 0, 0, 2, 0, 0, 0, 3, 0}).asReadOnlyBuffer();
        var indices = java.nio.IntBuffer.wrap(new int[]{0, 1, 2}).asReadOnlyBuffer();
        var uvs = java.nio.FloatBuffer.wrap(new float[]{0, 0, 1, 0, 0, 1}).asReadOnlyBuffer();
        positions.position(positions.limit());
        indices.position(indices.limit());
        var basis = MinecraftEntityPrimitives.tangentBasis(positions, indices, uvs, 0);
        assertEquals(1, basis.tangent().x());
        assertEquals(1, basis.bitangent().y());
        assertEquals(positions.limit(), positions.position());
        assertEquals(indices.limit(), indices.position());
        assertEquals(0, uvs.position());
    }

    @Test void adjacentCutoutTrianglesWithTheSameProgramShareOneRange() {
        var a = material("a", MinecraftEntityMesh.Program.MATERIAL);
        var b = material("b", MinecraftEntityMesh.Program.MATERIAL);
        MinecraftEntityMesh mesh = mesh(List.of(triangle(a, MinecraftEntityMesh.Coverage.CUTOUT),
                triangle(b, MinecraftEntityMesh.Coverage.CUTOUT)));
        assertEquals(1, MinecraftVulkanEntityUploader.geometryRanges(mesh).size());

        MinecraftEntityMesh stochastic = mesh(List.of(
                triangle(a, MinecraftEntityMesh.Coverage.STOCHASTIC),
                triangle(b, MinecraftEntityMesh.Coverage.STOCHASTIC)));
        assertEquals(1, MinecraftVulkanEntityUploader.geometryRanges(stochastic).size());
    }

    @Test void stochasticCoverageRemainsDistinctAtTheMeshApiBoundary() {
        assertInstanceOf(dev.comfyfluffy.caustica.api.geometry.MeshBuild.CoveragePolicy.Opaque.class,
                MinecraftVulkanEntityUploader.coveragePolicy(MinecraftEntityMesh.Coverage.OPAQUE));
        assertInstanceOf(dev.comfyfluffy.caustica.api.geometry.MeshBuild.CoveragePolicy.Cutout.class,
                MinecraftVulkanEntityUploader.coveragePolicy(MinecraftEntityMesh.Coverage.CUTOUT));
        var stochastic = assertInstanceOf(
                dev.comfyfluffy.caustica.api.geometry.MeshBuild.CoveragePolicy.Stochastic.class,
                MinecraftVulkanEntityUploader.coveragePolicy(MinecraftEntityMesh.Coverage.STOCHASTIC));
        assertEquals(.5f, stochastic.guideAlphaCutoff());
    }

    @Test void materialKeyPreservesOpticalProfileAndMediumBoundary() {
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "glass"), null,
                MinecraftEntityMesh.Program.MATERIAL,
                dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile.SMOOTH_DIELECTRIC, true);
        var key = MinecraftVulkanEntityUploader.materialKey(material);
        assertEquals(dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile.SMOOTH_DIELECTRIC,
                key.profile());
        assertEquals(dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTopology.MEDIUM_BOUNDARY,
                key.topology());
    }

    @Test void fullyCoveredGlassAndOpaqueSurfacesKeepSeparateVolumeRanges() {
        var glass = new MinecraftEntityMesh.Material(ResourceId.of("test", "glass"), null,
                MinecraftEntityMesh.Program.MATERIAL,
                dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile.SMOOTH_DIELECTRIC, true);
        var opaque = material("opaque", MinecraftEntityMesh.Program.MATERIAL);
        var source = mesh(List.of(triangle(glass, MinecraftEntityMesh.Coverage.OPAQUE),
                triangle(opaque, MinecraftEntityMesh.Coverage.OPAQUE)));
        assertEquals(2, MinecraftVulkanEntityUploader.geometryRanges(source).size());
    }

    @Test void cleanupContinuesAndAggregatesFailures() {
        ArrayList<Integer> closed = new ArrayList<>();
        var lifetime = new dev.comfyfluffy.caustica.vulkan.ResourceLifetime(
                () -> { closed.add(1); throw new IllegalStateException("first"); },
                () -> closed.add(2),
                () -> { closed.add(3); throw new IllegalArgumentException("third"); });
        Throwable failure = assertThrows(IllegalStateException.class, lifetime::close);

        assertEquals(List.of(1, 2, 3), closed);
        assertInstanceOf(IllegalStateException.class, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(IllegalArgumentException.class, failure.getSuppressed()[0]);
    }

    @Test void repeatedCleanupFailureDoesNotSkipRemainingResources() {
        var closed = new ArrayList<Integer>();
        var failure = new IllegalStateException("shared failure");
        var lifetime = new dev.comfyfluffy.caustica.vulkan.ResourceLifetime(
                () -> { closed.add(1); throw failure; },
                () -> { closed.add(2); throw failure; },
                () -> closed.add(3));
        assertSame(failure, assertThrows(IllegalStateException.class, lifetime::close));
        assertEquals(List.of(1, 2, 3), closed);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test void failedAllocationPreservesTheSameFailureThrownByLeaseCleanup() {
        var closed = new ArrayList<String>();
        var failure = new IllegalStateException("shared failure");
        var texture = MinecraftEntityMesh.Texture.standalone(ResourceId.of("test", "entity"));
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "entity"), texture,
                MinecraftEntityMesh.Program.MATERIAL);
        var uploader = uploader(unavailableGpu(method -> { throw failure; }),
                input -> lease(closed, "texture", failure));
        try (var job = uploader.prepareUpload(mesh(List.of(triangle(material, MinecraftEntityMesh.Coverage.CUTOUT))))) {
            assertSame(failure, assertThrows(IllegalStateException.class, job::finish));
        }
        assertEquals(List.of("texture"), closed);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test void uploadedEntityReleasesAllClaimsAfterCleanupFailure() {
        var closed = new ArrayList<Integer>();
        var failure = new IllegalStateException("first claim");
        var claims = new ArrayList<dev.comfyfluffy.caustica.api.resource.ResourceOwner>();
        for (int i = 0; i < 3; i++) {
            int index = i;
            claims.add(new dev.comfyfluffy.caustica.api.resource.ResourceOwner() {
                @Override public dev.comfyfluffy.caustica.api.resource.ResourceOwner retain() {
                    throw new AssertionError("closing must not retain");
                }
                @Override public void close() {
                    closed.add(index);
                    if (index == 0) throw failure;
                }
            });
        }
        var uploaded = new MinecraftVulkanEntityUploader.Uploaded(null, null,
                List.of(claims.get(0)), List.copyOf(claims.subList(1, 3)));
        assertSame(failure, assertThrows(IllegalStateException.class, uploaded::close));
        uploaded.close();
        assertEquals(List.of(0, 1, 2), closed);
    }

    private static MinecraftEntityMesh mesh(List<MinecraftEntityMesh.Triangle> triangles) {
        int count = triangles.size();
        float[] positions = new float[count * 9];
        float[] uvs = new float[count * 6];
        float[] colors = new float[count * 12];
        java.util.Arrays.fill(colors, 1.0f);
        int[] indices = new int[count * 3];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        return new MinecraftEntityMesh(positions, indices, uvs, colors, triangles, 1);
    }

    private static MinecraftEntityMesh.Material material(String name, MinecraftEntityMesh.Program program) {
        return new MinecraftEntityMesh.Material(ResourceId.of("test", name), null, program);
    }

    private static MinecraftEntityMesh.Triangle triangle(MinecraftEntityMesh.Material material,
                                                          MinecraftEntityMesh.Coverage coverage) {
        return new MinecraftEntityMesh.Triangle(material, coverage, 2.5f);
    }
}
