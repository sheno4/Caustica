package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftInstanceData;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.texture.BorrowedMinecraftTexture;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialKey;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTopology;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;

/** VMA-backed uploader for retained Minecraft entity geometry and per-triangle shader records. */
public final class MinecraftVulkanEntityUploader implements MinecraftEntityUploader {
    private static final int TEXTURE_PRESENT = 1;
    private final GpuDevice gpu;
    private final MinecraftMaterialLookup materials;
    private final MinecraftPrograms programs;
    private final EntityTextureResolver textures;

    public MinecraftVulkanEntityUploader(GpuDevice gpu, MinecraftMaterialLookup materials,
                                         MinecraftPrograms programs, EntityTextureResolver textures) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.programs = Objects.requireNonNull(programs, "programs");
        this.textures = Objects.requireNonNull(textures, "textures");
    }

    @Override public UploadedEntity upload(MinecraftEntityMesh source) {
        float[] positions = source.positions();
        int[] indices = source.indices();
        float[] uvs = source.uvs();
        float[] colors = source.vertexColors();
        VmaMappedBuffer position = create((long) positions.length * 4, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                bytes -> {
                    for (float value : positions) bytes.putFloat(value);
                });
        VmaMappedBuffer index = null, primitive = null, instance = null;
        TextureSet textureSet = null;
        try {
            index = create((long) indices.length * 4, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    bytes -> {
                        for (int value : indices) bytes.putInt(value);
                    });
            textureSet = resolveTextures(source);
            TextureSet resolved = textureSet;
            primitive = create((long) source.triangleCount() * MinecraftPrimitiveData.BYTE_SIZE,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    b -> writePrimitives(b, source, positions, indices, uvs, colors, resolved));
            instance = create(MinecraftInstanceData.BYTE_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    b -> new MinecraftInstanceData(new MinecraftInstanceData.Float3(1, 1, 1), 0,
                            new MinecraftInstanceData.SampledTexture2DIndex(0), 0).write(b));
            return uploaded(source, position, index, primitive, instance, textureSet);
        } catch (RuntimeException | Error failure) {
            Throwable cleanup = closeAll(instance, primitive, index, textureSet, position);
            if (cleanup != null) failure.addSuppressed(cleanup);
            throw failure;
        }
    }

    private UploadedEntity uploaded(MinecraftEntityMesh source, VmaMappedBuffer positions,
                                    VmaMappedBuffer indices, VmaMappedBuffer primitive,
                                    VmaMappedBuffer instance, TextureSet textureSet) {
        List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries = new ArrayList<>();
        for (GeometryRange range : geometryRanges(source)) {
            int first = range.firstTriangle(), end = range.endTriangle();
            MinecraftEntityMesh.Triangle triangle = source.triangles().get(first);
            ShaderData<MinecraftProgramTypes.PrimitiveData> binding = MinecraftProgramTypes.PRIMITIVE_DATA.data(
                    primitive.deviceAddressAt((long) first * MinecraftPrimitiveData.BYTE_SIZE).value());
            MeshBuild.CoveragePolicy policy = coveragePolicy(triangle.coverage());
            var surface = triangle.material().program() == MinecraftEntityMesh.Program.PORTAL
                    ? new MeshBuild.SurfaceSlot<>(programs.portalSurface(), binding, policy)
                    : new MeshBuild.SurfaceSlot<>(programs.materialSurface(), binding, policy);
            geometries.add(new MeshBuild.Geometry<>(surface, null, first * 3, (end - first) * 3));
        }
        MeshBuild<MinecraftProgramTypes.InstanceData> build = new MeshBuild<>(
                new MeshBuild.Stream(positions.deviceRange(), 12), null,
                new MeshBuild.Stream(indices.deviceRange(), 4), source.vertexCount(),
                new MeshBuild.IndexRevision(source.indexRevision()), geometries);
        return new Uploaded(build, MinecraftProgramTypes.INSTANCE_DATA.data(
                instance.deviceRange().address().value()),
                positions, indices, primitive, instance, textureSet);
    }

    static MeshBuild.CoveragePolicy coveragePolicy(MinecraftEntityMesh.Coverage coverage) {
        return switch (coverage) {
            case OPAQUE -> new MeshBuild.CoveragePolicy.Opaque();
            case CUTOUT -> new MeshBuild.CoveragePolicy.Cutout(.5f);
            case STOCHASTIC -> new MeshBuild.CoveragePolicy.Stochastic(.5f);
        };
    }

    static List<GeometryRange> geometryRanges(MinecraftEntityMesh source) {
        ArrayList<GeometryRange> ranges = new ArrayList<>();
        int first = 0;
        while (first < source.triangleCount()) {
            MinecraftEntityMesh.Triangle firstTriangle = source.triangles().get(first);
            int end = first + 1;
            while (end < source.triangleCount()
                    && compatible(firstTriangle, source.triangles().get(end))) {
                end++;
            }
            ranges.add(new GeometryRange(first, end));
            first = end;
        }
        return List.copyOf(ranges);
    }

    private static boolean compatible(MinecraftEntityMesh.Triangle a, MinecraftEntityMesh.Triangle b) {
        return a.material().program() == b.material().program() && a.coverage() == b.coverage();
    }

    private void writePrimitives(ByteBuffer bytes, MinecraftEntityMesh source, float[] positions,
                                 int[] indices, float[] uvs, float[] colors,
                                 TextureSet textureSet) {
        for (int t = 0; t < source.triangleCount(); t++) {
            var triangle = source.triangles().get(t);
            MinecraftPrimitiveData.Float2[] uv = new MinecraftPrimitiveData.Float2[3];
            for (int corner = 0; corner < 3; corner++) {
                int vertex = indices[t * 3 + corner];
                uv[corner] = new MinecraftPrimitiveData.Float2(
                        uvs[vertex * 2], uvs[vertex * 2 + 1]);
            }
            MinecraftPrimitiveData.Float4[] vertexColors = new MinecraftPrimitiveData.Float4[3];
            for (int corner = 0; corner < 3; corner++) {
                int vertex = indices[t * 3 + corner];
                vertexColors[corner] = new MinecraftPrimitiveData.Float4(colors[vertex * 4],
                        colors[vertex * 4 + 1], colors[vertex * 4 + 2], colors[vertex * 4 + 3]);
            }
            Integer descriptor = triangle.material().texture() == null ? null : textureSet.indices.get(triangle.material().texture());
            Integer samplerDescriptor = triangle.material().texture() == null
                    ? null : textureSet.samplerIndices.get(triangle.material().texture());
            TangentBasis basis = tangentBasis(positions, indices, uvs, t);
            var record = primitiveRecord(triangle, uv, vertexColors,
                    materials.resolveEntityOrFallback(materialKey(triangle.material())).materialIndex(),
                    descriptor, samplerDescriptor, basis);
            record.write(bytes.slice(t * MinecraftPrimitiveData.BYTE_SIZE, MinecraftPrimitiveData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN));
        }
        bytes.position(source.triangleCount() * MinecraftPrimitiveData.BYTE_SIZE);
    }

    static MinecraftPrimitiveData primitiveRecord(MinecraftEntityMesh.Triangle triangle,
                                                   MinecraftPrimitiveData.Float2[] uv,
                                                   MinecraftPrimitiveData.Float4[] colors,
                                                   int materialIndex, Integer descriptor, Integer samplerDescriptor,
                                                   TangentBasis basis) {
        if (colors.length != 3) throw new IllegalArgumentException("triangle needs three vertex colors");
        return new MinecraftPrimitiveData(uv, colors,
                    new MinecraftPrimitiveData.Float3(1, 1, 1), materialIndex,
                    new MinecraftPrimitiveData.SampledTexture2DIndex(descriptor == null ? 0 : descriptor),
                    new MinecraftPrimitiveData.SamplerIndex(samplerDescriptor == null ? 0 : samplerDescriptor),
                    descriptor == null ? 0 : TEXTURE_PRESENT, triangle.emission(),
                    basis.tangent(), basis.bitangent());
    }

    static MinecraftMaterialKey materialKey(MinecraftEntityMesh.Material material) {
        MinecraftMaterialProfile profile = switch (material.profile()) {
            case ROUGH_DIELECTRIC -> MinecraftMaterialProfile.ROUGH_DIELECTRIC;
            case SMOOTH_DIELECTRIC -> MinecraftMaterialProfile.SMOOTH_DIELECTRIC;
        };
        MinecraftMaterialTopology topology = material.mediumBoundary()
                ? MinecraftMaterialTopology.MEDIUM_BOUNDARY : MinecraftMaterialTopology.SURFACE;
        return new MinecraftMaterialKey(material.material(), null, profile, topology);
    }

    static TangentBasis tangentBasis(float[] positions, int[] indices, float[] uvs, int triangle) {
        int i0 = indices[triangle * 3], i1 = indices[triangle * 3 + 1], i2 = indices[triangle * 3 + 2];
        float x1 = positions[i1 * 3] - positions[i0 * 3];
        float y1 = positions[i1 * 3 + 1] - positions[i0 * 3 + 1];
        float z1 = positions[i1 * 3 + 2] - positions[i0 * 3 + 2];
        float x2 = positions[i2 * 3] - positions[i0 * 3];
        float y2 = positions[i2 * 3 + 1] - positions[i0 * 3 + 1];
        float z2 = positions[i2 * 3 + 2] - positions[i0 * 3 + 2];
        float u1 = uvs[i1 * 2] - uvs[i0 * 2], v1 = uvs[i1 * 2 + 1] - uvs[i0 * 2 + 1];
        float u2 = uvs[i2 * 2] - uvs[i0 * 2], v2 = uvs[i2 * 2 + 1] - uvs[i0 * 2 + 1];
        float determinant = u1 * v2 - u2 * v1;
        if (Math.abs(determinant) <= 1.0e-8f) return TangentBasis.ZERO;
        float inverse = 1.0f / determinant;
        return new TangentBasis(normalized((x1 * v2 - x2 * v1) * inverse,
                (y1 * v2 - y2 * v1) * inverse, (z1 * v2 - z2 * v1) * inverse),
                normalized((x2 * u1 - x1 * u2) * inverse,
                        (y2 * u1 - y1 * u2) * inverse, (z2 * u1 - z1 * u2) * inverse));
    }

    private static MinecraftPrimitiveData.Float3 normalized(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length <= 1.0e-8f) return new MinecraftPrimitiveData.Float3(0, 0, 0);
        return new MinecraftPrimitiveData.Float3(x / length, y / length, z / length);
    }

    private TextureSet resolveTextures(MinecraftEntityMesh source) {
        LinkedHashMap<MinecraftEntityMesh.Texture, BorrowedMinecraftTexture> leases = new LinkedHashMap<>();
        try {
            for (var triangle : source.triangles()) {
                if (triangle.material().texture() != null) {
                    leases.computeIfAbsent(triangle.material().texture(), texture ->
                            Objects.requireNonNull(textures.resolve(texture), "resolved texture"));
                }
            }
        } catch (RuntimeException | Error failure) {
            Throwable cleanup = closeAll(new LeaseCloser(leases.values()));
            if (cleanup != null) failure.addSuppressed(cleanup);
            throw failure;
        }
        if (leases.isEmpty()) return new TextureSet(null, null, Map.of(), Map.of(), List.of());
        GpuDescriptorRange<GpuDescriptorIndex.Resource> range = null;
        GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplerRange = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            range = gpu.descriptorHeap().allocateResources(leases.size());
            samplerRange = gpu.descriptorHeap().allocateSamplers(leases.size());
            int offset = 0;
            Map<MinecraftEntityMesh.Texture, Integer> indices = new LinkedHashMap<>();
            Map<MinecraftEntityMesh.Texture, Integer> samplerIndices = new LinkedHashMap<>();
            for (var entry : leases.entrySet()) {
                BorrowedMinecraftTexture borrowed = entry.getValue();
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(borrowed.vkImage()).viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(borrowed.format());
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(borrowed.baseMipLevel()).levelCount(borrowed.mipLevels())
                        .baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT image = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                        .pView(view).layout(borrowed.imageLayout());
                gpu.descriptorHeap().writer().writeResource(range, offset,
                        VkResourceDescriptorInfoEXT.calloc(stack).sType$Default().type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(d -> d.pImage(image)));
                gpu.descriptorHeap().writer().writeSampler(samplerRange, offset,
                        borrowed.sampler().write(VkSamplerCreateInfo.calloc(stack).sType$Default()));
                indices.put(entry.getKey(), Math.addExact(range.firstIndex().value(), offset));
                samplerIndices.put(entry.getKey(), Math.addExact(samplerRange.firstIndex().value(), offset));
                offset++;
            }
            return new TextureSet(range, samplerRange, Map.copyOf(indices), Map.copyOf(samplerIndices),
                    List.copyOf(leases.values()));
        } catch (RuntimeException | Error failure) {
            GpuDescriptorRange<GpuDescriptorIndex.Resource> allocated = range;
            GpuDescriptorRange<GpuDescriptorIndex.Sampler> allocatedSamplers = samplerRange;
            Throwable cleanup = closeAll(allocated == null ? null : allocated::destroy,
                    allocatedSamplers == null ? null : allocatedSamplers::destroy,
                    new LeaseCloser(leases.values()));
            if (cleanup != null) failure.addSuppressed(cleanup);
            throw failure;
        }
    }

    private VmaMappedBuffer create(long size, int extraUsage, Writer writer) {
        VmaMappedBuffer buffer = VmaMappedBuffer.create(gpu, size, extraUsage, "Minecraft entity upload");
        try {
            ByteBuffer bytes = buffer.mapped().order(ByteOrder.LITTLE_ENDIAN);
            writer.write(bytes);
            buffer.flush(0L, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    static Throwable closeAll(AutoCloseable... values) {
        Throwable failure = null;
        for (AutoCloseable value : values) {
            if (value == null) continue;
            try {
                value.close();
            } catch (Throwable next) {
                if (failure == null) failure = next;
                else failure.addSuppressed(next);
            }
        }
        return failure;
    }
    static final class TextureSet implements AutoCloseable {
        final GpuDescriptorRange<GpuDescriptorIndex.Resource> range;
        final GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplerRange;
        final Map<MinecraftEntityMesh.Texture, Integer> indices;
        final Map<MinecraftEntityMesh.Texture, Integer> samplerIndices;
        final List<BorrowedMinecraftTexture> leases;
        TextureSet(GpuDescriptorRange<GpuDescriptorIndex.Resource> range,
                   GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplerRange,
                   Map<MinecraftEntityMesh.Texture, Integer> indices,
                   Map<MinecraftEntityMesh.Texture, Integer> samplerIndices,
                   List<BorrowedMinecraftTexture> leases) {
            this.range = range;
            this.samplerRange = samplerRange;
            this.indices = indices;
            this.samplerIndices = samplerIndices;
            this.leases = leases;
        }
        @Override public void close() {
            throwIfFailed(closeAll(range == null ? null : range::destroy,
                    samplerRange == null ? null : samplerRange::destroy, new LeaseCloser(leases)));
        }
    }
    private record Uploaded(MeshBuild<MinecraftProgramTypes.InstanceData> build,
                            ShaderData<MinecraftProgramTypes.InstanceData> instanceData,
                            VmaMappedBuffer positions, VmaMappedBuffer indices,
                            VmaMappedBuffer primitive, VmaMappedBuffer instance,
                            TextureSet textures) implements UploadedEntity {
        @Override public void close() {
            throwIfFailed(closeAll(textures, instance, primitive, indices, positions));
        }
    }
    record GeometryRange(int firstTriangle, int endTriangle) { }
    record TangentBasis(MinecraftPrimitiveData.Float3 tangent, MinecraftPrimitiveData.Float3 bitangent) {
        static final TangentBasis ZERO = new TangentBasis(new MinecraftPrimitiveData.Float3(0, 0, 0),
                new MinecraftPrimitiveData.Float3(0, 0, 0));
    }
    private record LeaseCloser(Collection<? extends AutoCloseable> leases) implements AutoCloseable {
        @Override public void close() {
            throwIfFailed(closeAll(leases.toArray(AutoCloseable[]::new)));
        }
    }
    private static void throwIfFailed(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException(failure);
    }
    @FunctionalInterface private interface Writer { void write(ByteBuffer bytes); }
}
