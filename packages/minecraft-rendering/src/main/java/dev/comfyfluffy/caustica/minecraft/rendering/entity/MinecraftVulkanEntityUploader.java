package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftInstanceData;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.texture.BorrowedMinecraftTexture;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialKey;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTopology;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftEntityPrimitives.TextureBinding;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static dev.comfyfluffy.caustica.vulkan.ResourceLifetime.closeAfterFailure;

/** VMA-backed uploader for retained Minecraft entity geometry and per-triangle shader records. */
public final class MinecraftVulkanEntityUploader implements MinecraftEntityUploader {
    private final GpuDevice gpu;
    private final MinecraftMaterialLookup materials;
    private final MinecraftPrograms programs;
    private final EntityTextureResolver textures;
    private final ResourceFactory resources;

    public MinecraftVulkanEntityUploader(GpuDevice gpu, MinecraftMaterialLookup materials,
                                         MinecraftPrograms programs, EntityTextureResolver textures,
                                         ResourceFactory resources) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.programs = Objects.requireNonNull(programs, "programs");
        this.textures = Objects.requireNonNull(textures, "textures");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    @Override public UploadJob prepareUpload(MinecraftEntityMesh source) {
        var captured = captureTextures(source);
        try {
            var materialIndices = new HashMap<MinecraftEntityMesh.Material, Integer>();
            for (var triangle : source.triangles()) {
                var material = triangle.material();
                if (!materialIndices.containsKey(material)) {
                    materialIndices.put(material,
                            materials.resolveEntityOrFallback(materialKey(material)).materialIndex());
                }
            }
            return new PackingJob(source, captured, Map.copyOf(materialIndices));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, captured::close);
            throw failure;
        }
    }

    private final class PackingJob implements UploadJob {
        final MinecraftEntityMesh source;
        final Map<MinecraftEntityMesh.Material, Integer> materialIndices;
        CapturedTextures captured;

        PackingJob(MinecraftEntityMesh source, CapturedTextures captured,
                   Map<MinecraftEntityMesh.Material, Integer> materialIndices) {
            this.source = source;
            this.captured = captured;
            this.materialIndices = materialIndices;
        }

        @Override public UploadedEntity finish() {
            var claimed = captured;
            captured = null;
            TextureSet textures = allocateTextures(claimed);
            List<Runnable> releases = new ArrayList<>();
            releases.add(textures::close);
            VmaMappedBuffer positions, indices, primitive, instance;
            try {
                positions = VmaMappedBuffer.createAsync(gpu, (long) source.vertexCount() * 12,
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, "Minecraft entity positions");
                releases.add(positions::close);
                indices = VmaMappedBuffer.createAsync(gpu, (long) source.triangleCount() * 12,
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, "Minecraft entity indices");
                releases.add(indices::close);
                primitive = VmaMappedBuffer.create(gpu, MinecraftEntityPrimitives.byteSize(source.triangleCount()),
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Minecraft entity primitives");
                releases.add(primitive::close);
                instance = VmaMappedBuffer.create(gpu, MinecraftInstanceData.BYTE_SIZE,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Minecraft entity instance");
                releases.add(instance::close);
                var positionValues = source.positions();
                var indexValues = source.indices();
                write(positions, (long) positionValues.capacity() * 4, bytes -> bytes.asFloatBuffer().put(positionValues));
                write(indices, (long) indexValues.capacity() * 4, bytes -> bytes.asIntBuffer().put(indexValues));
                write(primitive, MinecraftEntityPrimitives.byteSize(source.triangleCount()),
                        bytes -> MinecraftEntityPrimitives.write(bytes, source, indexValues, source.uvs(),
                                source.vertexColors(), textures.bindings, materialIndices));
                write(instance, MinecraftInstanceData.BYTE_SIZE,
                        bytes -> new MinecraftInstanceData(new MinecraftInstanceData.Float3(1, 1, 1), 0,
                                new MinecraftInstanceData.SampledTexture2DIndex(0), 0).write(bytes));
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(failure, releases.reversed().toArray(Runnable[]::new));
                throw failure;
            }
            return uploaded(source, positions, indices, primitive, instance, textures);
        }

        @Override public void close() {
            var released = captured;
            captured = null;
            if (released != null) released.close();
        }
    }

    private UploadedEntity uploaded(MinecraftEntityMesh source, VmaMappedBuffer positions,
                                    VmaMappedBuffer indices, VmaMappedBuffer primitive,
                                    VmaMappedBuffer instance, TextureSet textureSet) {
        ResourceLifetime bindingLifetime = new ResourceLifetime(textureSet::close, primitive::close);
        // Each cleanup handle switches from the raw allocation to its shared owner only after creation succeeds.
        Runnable releasePositions = positions::close;
        Runnable releaseIndices = indices::close;
        Runnable releaseBinding = bindingLifetime::close;
        Runnable releaseInstance = instance::close;
        // MeshBuild borrows its shader data; each created value still owns a separate resource claim.
        List<ResourceOwner> claims = new ArrayList<>();
        try {
            ResourceOwner positionGeneration = resources.create(positions::close);
            releasePositions = positionGeneration::close;
            ResourceOwner indexGeneration = resources.create(indices::close);
            releaseIndices = indexGeneration::close;
            ResourceOwner bindingGeneration = resources.create(bindingLifetime::close);
            releaseBinding = bindingGeneration::close;
            ResourceOwner instanceGeneration = resources.create(instance::close);
            releaseInstance = instanceGeneration::close;
            List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries = new ArrayList<>();
            for (GeometryRange range : geometryRanges(source)) {
                int first = range.firstTriangle(), end = range.endTriangle();
                MinecraftEntityMesh.Triangle triangle = source.triangles().get(first);
                ShaderData<MinecraftProgramTypes.PrimitiveData> binding = MinecraftProgramTypes.PRIMITIVE_DATA.data(
                        primitive.deviceAddressAt((long) first * MinecraftPrimitiveData.BYTE_SIZE).value(),
                        bindingGeneration);
                claims.add(binding);
                MeshBuild.CoveragePolicy policy = coveragePolicy(triangle.coverage());
                var surface = triangle.material().program() == MinecraftEntityMesh.Program.PORTAL
                        ? new MeshBuild.SurfaceSlot<>(programs.portalSurface(), binding, policy)
                        : new MeshBuild.SurfaceSlot<>(programs.materialSurface(), binding, policy);
                var volume = triangle.material().mediumBoundary()
                        ? new MeshBuild.VolumeSlot<>(programs.dielectricVolume(), binding) : null;
                geometries.add(new MeshBuild.Geometry<>(surface, volume, first * 3, (end - first) * 3));
            }
            MeshBuild<MinecraftProgramTypes.InstanceData> build = new MeshBuild<>(
                    new MeshBuild.Stream(positions.deviceRange(), 12, positionGeneration),
                    new MeshBuild.Stream(indices.deviceRange(), 4, indexGeneration), source.vertexCount(),
                    new MeshBuild.IndexRevision(source.indexRevision()), MeshBuild.BuildPolicy.REFITTABLE, geometries);
            ShaderData<MinecraftProgramTypes.InstanceData> instanceData = MinecraftProgramTypes.INSTANCE_DATA.data(
                    instance.deviceRange().address().value(), instanceGeneration);

            claims.add(instanceData);
            return new Uploaded(build, instanceData, List.copyOf(claims),
                    List.of(positionGeneration, indexGeneration, bindingGeneration, instanceGeneration));
        } catch (RuntimeException | Error failure) {
            ResourceLifetime shaderClaims = new ResourceLifetime(claims.stream()
                    .map(owner -> (Runnable) owner::close).toArray(Runnable[]::new));
            closeAfterFailure(failure, shaderClaims::close, releasePositions, releaseIndices,
                    releaseBinding, releaseInstance);
            throw failure;
        }
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
        return a.material().program() == b.material().program() && a.coverage() == b.coverage()
                && a.material().mediumBoundary() == b.material().mediumBoundary();
    }

    static MinecraftMaterialKey materialKey(MinecraftEntityMesh.Material material) {
        MinecraftMaterialTopology topology = material.mediumBoundary()
                ? MinecraftMaterialTopology.MEDIUM_BOUNDARY : MinecraftMaterialTopology.SURFACE;
        return new MinecraftMaterialKey(material.material(), null, material.profile(), topology);
    }

    private CapturedTextures captureTextures(MinecraftEntityMesh source) {
        LinkedHashMap<MinecraftEntityMesh.Texture, BorrowedMinecraftTexture> leases = new LinkedHashMap<>();
        try {
            for (var triangle : source.triangles()) {
                var texture = triangle.material().texture();
                if (texture != null && !leases.containsKey(texture)) {
                    leases.put(texture, Objects.requireNonNull(textures.resolve(texture), "resolved texture"));
                }
            }
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, leases.values().stream()
                    .map(lease -> (Runnable) lease::close).toArray(Runnable[]::new));
            throw failure;
        }
        return new CapturedTextures(leases);
    }

    /** Consumes the captured host leases on both successful allocation and failure. */
    private TextureSet allocateTextures(CapturedTextures captured) {
        var leases = captured.leases;
        if (leases.isEmpty()) return new TextureSet(null, null, Map.of(), List.of());
        List<Runnable> releases = new ArrayList<>();
        releases.add(captured::close);
        try {
            var range = gpu.descriptorHeap().allocateResources(leases.size());
            releases.add(range::destroy);
            var samplerRange = gpu.descriptorHeap().allocateSamplers(leases.size());
            releases.add(samplerRange::destroy);
            Map<MinecraftEntityMesh.Texture, TextureBinding> bindings = new LinkedHashMap<>();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int offset = 0;
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
                    bindings.put(entry.getKey(), new TextureBinding(
                            Math.addExact(range.firstIndex().value(), offset),
                            Math.addExact(samplerRange.firstIndex().value(), offset)));
                    offset++;
                }
            }
            return new TextureSet(range, samplerRange, Map.copyOf(bindings),
                    List.copyOf(leases.values()));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, releases.reversed().toArray(Runnable[]::new));
            throw failure;
        }
    }

    private static void write(VmaMappedBuffer buffer, long size, Consumer<ByteBuffer> writer) {
        writer.accept(buffer.mapped().order(ByteOrder.LITTLE_ENDIAN));
        buffer.flush(0L, size);
    }

    static final class TextureSet implements AutoCloseable {
        final Map<MinecraftEntityMesh.Texture, TextureBinding> bindings;
        private final ResourceLifetime lifetime;
        TextureSet(GpuDescriptorRange<GpuDescriptorIndex.Resource> range,
                   GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplerRange,
                   Map<MinecraftEntityMesh.Texture, TextureBinding> bindings,
                   List<BorrowedMinecraftTexture> leases) {
            this.bindings = bindings;
            List<Runnable> releases = new ArrayList<>();
            if (range != null) releases.add(range::destroy);
            if (samplerRange != null) releases.add(samplerRange::destroy);
            for (BorrowedMinecraftTexture lease : leases) releases.add(lease::close);
            lifetime = new ResourceLifetime(releases.toArray(Runnable[]::new));
        }
        @Override public void close() {
            lifetime.close();
        }
    }
    record Uploaded(MeshBuild<MinecraftProgramTypes.InstanceData> build,
                    ShaderData<MinecraftProgramTypes.InstanceData> instanceData,
                    ResourceLifetime lifetime) implements UploadedEntity {
        Uploaded(MeshBuild<MinecraftProgramTypes.InstanceData> build,
                 ShaderData<MinecraftProgramTypes.InstanceData> instanceData,
                 List<ResourceOwner> shaderClaims, List<ResourceOwner> bufferClaims) {
            this(build, instanceData, new ResourceLifetime(
                    java.util.stream.Stream.concat(shaderClaims.stream(), bufferClaims.stream())
                            .map(owner -> (Runnable) owner::close).toArray(Runnable[]::new)));
        }
        @Override public void close() {
            lifetime.close();
        }
    }
    record GeometryRange(int firstTriangle, int endTriangle) { }
    private record CapturedTextures(Map<MinecraftEntityMesh.Texture, BorrowedMinecraftTexture> leases,
                                    ResourceLifetime lifetime)
            implements AutoCloseable {
        CapturedTextures(Map<MinecraftEntityMesh.Texture, BorrowedMinecraftTexture> leases) {
            this(leases, new ResourceLifetime(leases.values().stream()
                    .map(lease -> (Runnable) lease::close).toArray(Runnable[]::new)));
        }
        @Override public void close() { lifetime.close(); }
    }
}
