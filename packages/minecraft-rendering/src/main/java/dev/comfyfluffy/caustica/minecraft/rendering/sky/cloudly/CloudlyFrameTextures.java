package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import dev.comfyfluffy.caustica.vulkan.VmaMappedHostBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

import static dev.comfyfluffy.caustica.vulkan.ResourceLifetime.closeAfterFailure;
import static org.lwjgl.vulkan.VK10.*;

/** Hash-captured original 2D and cube mip payloads, uploaded without decoding or resampling. */
public final class CloudlyFrameTextures {
    private static final int USAGE = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    private static final List<String> CUBE_FACES = List.of("+X", "-X", "+Y", "-Y", "+Z", "-Z");
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private CloudlyFrameTextures() { }

    public enum Kind {
        TWO_D(1, VK_IMAGE_VIEW_TYPE_2D, 0),
        CUBE(6, VK_IMAGE_VIEW_TYPE_CUBE, VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT);
        private final int layers, viewType, flags;
        Kind(int layers, int viewType, int flags) { this.layers = layers; this.viewType = viewType; this.flags = flags; }
        public int layers() { return layers; }
    }

    /** Block geometry determines byte counts even for cube mip levels smaller than a compressed block. */
    public enum Format {
        BGRA8("PF_B8G8R8A8", VK_FORMAT_B8G8R8A8_UNORM, 1, 4),
        RGBA16F("PF_FloatRGBA", VK_FORMAT_R16G16B16A16_SFLOAT, 1, 8),
        BC6H("PF_BC6H", VK_FORMAT_BC6H_UFLOAT_BLOCK, 4, 16);
        private final String nativeName;
        private final int vulkan, block, bytesPerBlock;
        Format(String nativeName, int vulkan, int block, int bytesPerBlock) {
            this.nativeName = nativeName; this.vulkan = vulkan; this.block = block; this.bytesPerBlock = bytesPerBlock;
        }
        public int vulkan() { return vulkan; }
        public long faceByteSize(int width, int height) {
            return Math.multiplyExact(Math.multiplyExact(Math.ceilDiv((long) width, block), Math.ceilDiv((long) height, block)), bytesPerBlock);
        }
        public long rowPitch(int width) { return Math.multiplyExact(Math.ceilDiv((long) width, block), bytesPerBlock); }
    }

    /** Every returned view has independent buffer position; payload contents stay immutable. */
    public record Mip(int level, int width, int height, long faceBytes, String sha256, ByteBuffer bytes) {
        public Mip {
            ByteBuffer captured = ByteBuffer.allocate(bytes.remaining());
            captured.put(bytes.duplicate()).flip();
            bytes = captured.asReadOnlyBuffer();
        }
        @Override public ByteBuffer bytes() { return bytes.asReadOnlyBuffer(); }
    }

    public record TextureSpec(String name, List<String> bindingAliases, Kind kind, Format format,
                              int width, int height, List<Mip> mips) {
        public TextureSpec { bindingAliases = List.copyOf(bindingAliases); mips = List.copyOf(mips); }
        public long byteSize() { return mips.stream().mapToLong(mip -> mip.bytes().remaining()).sum(); }
    }

    /** Complete verified bytes are captured before GPU allocation, so later file edits cannot race recording. */
    public record Source(Path manifest, String manifestSha256, List<TextureSpec> textures) {
        public Source { textures = List.copyOf(textures); }
    }

    public static Source load(Path path) throws IOException { return load(path, null); }

    /** An optional expected manifest digest pins the complete private texture revision as well as its mips. */
    public static Source load(Path path, String expectedManifestSha256) throws IOException {
        Path actual = path.toRealPath();
        if (Files.size(actual) > 4L * 1024 * 1024) throw new IOException("Private frame texture manifest is too large");
        byte[] manifestBytes = Files.readAllBytes(actual);
        String manifestHash = hash(manifestBytes);
        if (expectedManifestSha256 != null && !manifestHash.equals(expectedManifestSha256)) {
            throw new IOException("Private frame texture manifest SHA-256 mismatch");
        }
        try {
            JsonObject root = JsonParser.parseString(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            require(root.get("schemaVersion").getAsInt() == 1, "Unsupported frame texture schema");
            var entries = root.getAsJsonArray("textures");
            require(!entries.isEmpty() && entries.size() <= 64, "Invalid frame texture count");
            List<TextureSpec> textures = new ArrayList<>();
            Set<String> names = new HashSet<>(), aliases = new HashSet<>();
            long totalBytes = 0;
            for (JsonElement element : entries) {
                checkInterrupted();
                JsonObject entry = element.getAsJsonObject();
                String name = entry.get("resource").getAsString();
                require(!name.isBlank() && names.add(name), "Duplicate or blank frame texture name");
                Kind kind = switch (entry.get("nativeClass").getAsString()) {
                    case "Texture2D" -> Kind.TWO_D;
                    case "TextureCube" -> Kind.CUBE;
                    default -> throw new IOException("Frame textures must be 2D or cube images");
                };
                Format format = Arrays.stream(Format.values()).filter(candidate -> candidate.nativeName.equals(entry.get("nativePixelFormat").getAsString()))
                        .findFirst().orElseThrow(() -> new IOException("Unsupported native frame texture format"));
                int width = entry.get("width").getAsInt(), height = entry.get("height").getAsInt();
                int levels = entry.get("mipLevels").getAsInt();
                require(width > 0 && height > 0 && width <= 16384 && height <= 16384
                        && (kind != Kind.CUBE || width == height) && entry.get("depth").getAsInt() == 1
                        && entry.get("arrayLayers").getAsInt() == kind.layers && entry.get("firstMip").getAsInt() == 0,
                        "Invalid native frame texture dimensions");
                require(levels > 0 && levels <= 32 - Integer.numberOfLeadingZeros(Math.max(width, height)), "Invalid frame texture mip count");
                require(entry.getAsJsonObject("vulkanFormat").get("value").getAsInt() == format.vulkan, "Native and Vulkan frame formats differ");
                JsonObject descriptor = entry.getAsJsonObject("descriptor");
                require(descriptor.get("format").getAsInt() == format.vulkan
                        && descriptor.get("layerCount").getAsInt() == kind.layers
                        && descriptor.get("levelCount").getAsInt() == levels
                        && descriptor.get("baseMipLevel").getAsInt() == 0 && descriptor.get("baseArrayLayer").getAsInt() == 0
                        && descriptor.get("imageType").getAsString().equals("VK_IMAGE_TYPE_2D")
                        && descriptor.get("viewType").getAsString().equals(kind == Kind.CUBE ? "VK_IMAGE_VIEW_TYPE_CUBE" : "VK_IMAGE_VIEW_TYPE_2D")
                        && descriptor.get("kind").getAsString().equals(kind == Kind.CUBE ? "sampledImageCubeDescriptor" : "sampledImage2DDescriptor")
                        && vector(descriptor, "imageExtent").equals(List.of(width, height, 1)),
                        "Frame texture descriptor shape differs");
                List<String> bindingAliases = new ArrayList<>();
                for (JsonElement alias : entry.getAsJsonArray("bindingAliases")) {
                    String value = alias.getAsString();
                    require(!value.isBlank() && aliases.add(value), "Duplicate or blank frame texture binding alias");
                    bindingAliases.add(value);
                }
                if (kind == Kind.CUBE) {
                    require(entry.getAsJsonArray("faceOrder").asList().stream().map(JsonElement::getAsString).toList().equals(CUBE_FACES),
                            "Native cube faces are not in Vulkan layer order");
                }
                List<Mip> mips = new ArrayList<>();
                for (JsonElement mipElement : entry.getAsJsonArray("mips")) {
                    JsonObject mip = mipElement.getAsJsonObject();
                    int level = mips.size(), sx = Math.max(1, width >> level), sy = Math.max(1, height >> level);
                    require(level < levels && mip.get("level").getAsInt() == level && mip.get("width").getAsInt() == sx
                            && mip.get("height").getAsInt() == sy && mip.get("depth").getAsInt() == 1
                            && mip.get("arrayLayers").getAsInt() == kind.layers, "Invalid native frame texture mip shape");
                    long faceBytes = format.faceByteSize(sx, sy), size = Math.multiplyExact(faceBytes, kind.layers);
                    totalBytes = Math.addExact(totalBytes, size);
                    require(totalBytes <= MAX_BYTES && mip.get("bytes").getAsLong() == size, "Frame texture mip byte count differs from block geometry");
                    Path relative = Path.of(mip.get("path").getAsString());
                    require(!relative.isAbsolute(), "Frame mip path must be relative");
                    Path resolved = actual.getParent().resolve(relative).normalize();
                    require(resolved.startsWith(actual.getParent()), "Frame mip path leaves its manifest directory");
                    Path payloadPath = resolved.toRealPath();
                    require(payloadPath.startsWith(actual.getParent()) && Files.size(payloadPath) == size, "Frame mip file has an invalid boundary or size");
                    byte[] bytes = Files.readAllBytes(payloadPath);
                    String sha = mip.get("sha256").getAsString();
                    require(hash(bytes).equals(sha), "Frame mip SHA-256 mismatch");
                    validateFaces(mip, kind, format, level, sx, sy, faceBytes, bytes);
                    mips.add(new Mip(level, sx, sy, faceBytes, sha, ByteBuffer.wrap(bytes)));
                }
                require(mips.size() == levels, "Missing native frame mip payloads");
                require(entry.get("payloadBytes").getAsLong() == mips.stream().mapToLong(mip -> mip.bytes().remaining()).sum(), "Frame texture total byte count differs");
                textures.add(new TextureSpec(name, bindingAliases, kind, format, width, height, mips));
            }
            for (TextureSpec texture : textures) for (String alias : texture.bindingAliases()) {
                require(!names.contains(alias) || texture.name().equals(alias), "Frame binding alias names another texture");
            }
            return new Source(actual, manifestHash, textures);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException | ArithmeticException | NullPointerException failure) {
            throw new IOException("Invalid private frame texture manifest " + actual, failure);
        }
    }

    private static void validateFaces(JsonObject mip, Kind kind, Format format, int level, int width, int height,
                                      long faceBytes, byte[] bytes) throws IOException {
        var faces = mip.getAsJsonArray("faces");
        require(faces.size() == kind.layers, "Missing native cube face regions");
        for (int layer = 0; layer < kind.layers; layer++) {
            JsonObject face = faces.get(layer).getAsJsonObject();
            long offset = Math.multiplyExact(layer, faceBytes);
            require(face.get("layer").getAsInt() == layer && face.get("bufferOffset").getAsLong() == offset
                    && face.get("byteCount").getAsLong() == faceBytes && face.get("rowPitchBytes").getAsLong() == format.rowPitch(width)
                    && face.get("face").getAsString().equals(kind == Kind.CUBE ? CUBE_FACES.get(layer) : "2D"), "Invalid native face byte region");
            require(hash(Arrays.copyOfRange(bytes, Math.toIntExact(offset), Math.toIntExact(offset + faceBytes))).equals(face.get("sha256").getAsString()),
                    "Native face SHA-256 mismatch");
            JsonObject copy = face.getAsJsonObject("copyRegion");
            require(copy.get("bufferOffset").getAsLong() == offset && copy.get("mipLevel").getAsInt() == level
                    && copy.get("baseArrayLayer").getAsInt() == layer && copy.get("layerCount").getAsInt() == 1
                    && copy.get("bufferRowLength").getAsInt() == 0 && copy.get("bufferImageHeight").getAsInt() == 0
                    && vector(copy, "imageOffset").equals(List.of(0, 0, 0))
                    && vector(copy, "imageExtent").equals(List.of(width, height, 1)), "Invalid frame texture copy region");
        }
    }

    private static List<Integer> vector(JsonObject entry, String key) {
        return entry.getAsJsonArray(key).asList().stream().map(JsonElement::getAsInt).toList();
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Original frame texture preparation cancelled");
    }

    /** Typed views keep cube descriptors separate from ordinary 2D descriptors. */
    public sealed interface Texture permits Image2D, Cube {
        TextureSpec source();
        GpuDescriptorIndex.Resource sampledIndex();
        GpuDescriptorIndex.Sampler samplerIndex();
    }
    public record Image2D(TextureSpec source, GpuDescriptorIndex.Resource sampledIndex,
                          GpuDescriptorIndex.Sampler samplerIndex) implements Texture {
        public Image2D { if (source.kind() != Kind.TWO_D) throw new IllegalArgumentException("2D descriptor requires a 2D source"); }
    }
    public record Cube(TextureSpec source, GpuDescriptorIndex.Resource sampledIndex,
                       GpuDescriptorIndex.Sampler samplerIndex) implements Texture {
        public Cube { if (source.kind() != Kind.CUBE) throw new IllegalArgumentException("Cube descriptor requires a cube source"); }
    }

    public sealed interface Completion permits Ready, Failed, Cancelled { }
    public record Failed(Throwable failure) implements Completion { }
    public record Cancelled() implements Completion { }

    /** An immutable completed revision; each frame or GPU job retains its own claim while using descriptors. */
    public static final class Ready implements Completion, AutoCloseable {
        private final Source source;
        private final Map<String, Texture> textures;
        private final Map<String, GpuDescriptorIndex.Resource> descriptors;
        private final ResourceOwner owner;
        Ready(Source source, Map<String, Texture> textures, ResourceOwner owner) {
            this.source = source; this.textures = Map.copyOf(textures); this.owner = owner;
            Map<String, GpuDescriptorIndex.Resource> aliases = new LinkedHashMap<>();
            textures.forEach((name, texture) -> {
                aliases.put(name, texture.sampledIndex());
                texture.source().bindingAliases().forEach(alias -> aliases.put(alias, texture.sampledIndex()));
            });
            descriptors = Map.copyOf(aliases);
        }
        public Source source() { return source; }
        public Map<String, Texture> textures() { return textures; }
        public Map<String, GpuDescriptorIndex.Resource> descriptors() { return descriptors; }
        public Image2D image2D(String name) {
            if (textures.get(name) instanceof Image2D image) return image;
            throw new IllegalArgumentException("Original texture is not 2D: " + name);
        }
        public Cube cube(String name) {
            if (textures.get(name) instanceof Cube cube) return cube;
            throw new IllegalArgumentException("Original texture is not a cube: " + name);
        }
        public ResourceOwner retain() { return owner.retain(); }
        @Override public void close() { owner.close(); }
    }

    /**
     * Caller supplies source sampler states and the logical-device BC feature contract. Physical-device
     * support alone does not prove textureCompressionBC was enabled. Ready is published only after the
     * asynchronous copies complete; callbacks must return promptly without throwing.
     */
    public static GpuComputeJob prepare(GpuDevice gpu, GpuComputeQueue compute, ResourceFactory resources,
                                        Source source, Map<String, CloudlyVolumeBake.SamplerSpec> samplerSpecs,
                                        boolean textureCompressionBCEnabled, Consumer<? super Completion> completion) throws IOException {
        Objects.requireNonNull(completion);
        samplerSpecs = Map.copyOf(samplerSpecs);
        if (!textureCompressionBCEnabled && source.textures().stream().anyMatch(texture -> texture.format() == Format.BC6H)) {
            throw new UnsupportedOperationException("Original BC6H frame textures require enabled textureCompressionBC");
        }
        for (TextureSpec texture : source.textures()) if (!samplerSpecs.containsKey(texture.name())) {
            throw new IllegalArgumentException("Original frame texture sampler is missing: " + texture.name());
        }
        List<Upload> uploads = new ArrayList<>();
        GpuDescriptorRange<GpuDescriptorIndex.Resource> images = null;
        GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplers = null;
        ResourceOwner owner = null, jobOwner = null;
        try {
            for (TextureSpec texture : source.textures()) {
                checkInterrupted();
                uploads.add(allocate(gpu, compute, texture, samplerSpecs.get(texture.name())));
            }
            images = gpu.descriptorHeap().allocateResources(uploads.size());
            samplers = gpu.descriptorHeap().allocateSamplers(uploads.size());
            writeDescriptors(gpu, images, samplers, uploads, samplerSpecs);
            var imageRange = images;
            var samplerRange = samplers;
            List<VmaImageAllocation> allocations = uploads.stream().map(Upload::image).toList();
            owner = resources.create(() -> new ResourceLifetime(imageRange::destroy, samplerRange::destroy,
                    () -> new ResourceLifetime(allocations.stream().<Runnable>map(image -> image::close).toArray(Runnable[]::new)).close()).close());
            Map<String, Texture> textureViews = new LinkedHashMap<>();
            for (int index = 0; index < uploads.size(); index++) {
                TextureSpec texture = uploads.get(index).spec();
                var imageIndex = new GpuDescriptorIndex.Resource(Math.addExact(images.firstIndex().value(), index));
                var samplerIndex = new GpuDescriptorIndex.Sampler(Math.addExact(samplers.firstIndex().value(), index));
                textureViews.put(texture.name(), texture.kind() == Kind.CUBE ? new Cube(texture, imageIndex, samplerIndex) : new Image2D(texture, imageIndex, samplerIndex));
            }
            Ready revision = new Ready(source, textureViews, owner);
            ResourceLifetime staging = new ResourceLifetime(uploads.stream().<Runnable>map(upload -> upload.staging()::close).toArray(Runnable[]::new));
            List<Upload> copies = List.copyOf(uploads);
            jobOwner = revision.retain();
            return compute.submit(command -> record(command, copies), List.of(jobOwner), result -> {
                GpuComputeCompletion retired = retireStaging(result, staging);
                if (retired instanceof GpuComputeCompletion.Succeeded) {
                    try { completion.accept(revision); }
                    catch (RuntimeException | Error failure) { revision.close(); throw failure; }
                } else {
                    revision.close();
                    completion.accept(retired instanceof GpuComputeCompletion.Failed failed ? new Failed(failed.failure()) : new Cancelled());
                }
            });
        } catch (IOException | RuntimeException | Error failure) {
            ResourceOwner allocatedOwner = owner, allocatedJobOwner = jobOwner;
            var allocatedImages = images;
            var allocatedSamplers = samplers;
            closeAfterFailure(failure,
                    () -> { if (allocatedJobOwner != null) allocatedJobOwner.close(); },
                    () -> {
                        if (allocatedOwner != null) allocatedOwner.close();
                        else new ResourceLifetime(
                                () -> { if (allocatedImages != null) allocatedImages.destroy(); },
                                () -> { if (allocatedSamplers != null) allocatedSamplers.destroy(); },
                                () -> new ResourceLifetime(uploads.stream().<Runnable>map(upload -> upload.image()::close).toArray(Runnable[]::new)).close()).close();
                    },
                    () -> new ResourceLifetime(uploads.stream().<Runnable>map(upload -> upload.staging()::close).toArray(Runnable[]::new)).close());
            throw failure;
        }
    }

    private static Upload allocate(GpuDevice gpu, GpuComputeQueue compute, TextureSpec texture,
                                    CloudlyVolumeBake.SamplerSpec sampler) throws IOException {
        validateImageSupport(gpu, texture, sampler);
        VmaImageAllocation image = null;
        VmaMappedHostBuffer staging = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo create = VkImageCreateInfo.calloc(stack).sType$Default().flags(texture.kind().flags)
                    .imageType(VK_IMAGE_TYPE_2D).format(texture.format().vulkan).mipLevels(texture.mips().size())
                    .arrayLayers(texture.kind().layers).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(USAGE).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(texture.width(), texture.height(), 1);
            int[] families = compute.sharedQueueFamilyIndices();
            if (families.length > 1) create.sharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(families));
            image = VmaImageAllocation.create(gpu, create, "Original frame texture " + texture.name());
            staging = VmaMappedHostBuffer.create(gpu, texture.byteSize(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, "Original frame texture upload " + texture.name());
            ByteBuffer mapping = staging.mapped();
            long[] offsets = mipOffsets(texture);
            for (int level = 0; level < offsets.length; level++) {
                checkInterrupted();
                ByteBuffer bytes = texture.mips().get(level).bytes();
                mapping.slice(Math.toIntExact(offsets[level]), bytes.remaining()).put(bytes);
            }
            staging.flush(0, staging.byteSize());
            return new Upload(texture, image, staging, offsets);
        } catch (IOException | RuntimeException | Error failure) {
            VmaImageAllocation allocation = image;
            VmaMappedHostBuffer buffer = staging;
            closeAfterFailure(failure, () -> { if (buffer != null) buffer.close(); }, () -> { if (allocation != null) allocation.close(); });
            throw failure;
        }
    }

    private static void validateImageSupport(GpuDevice gpu, TextureSpec texture, CloudlyVolumeBake.SamplerSpec sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var format = VkFormatProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceFormatProperties2(gpu.vk().getPhysicalDevice(), texture.format().vulkan, format);
            int required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            if (sampler.minFilter() == VK_FILTER_LINEAR || sampler.magFilter() == VK_FILTER_LINEAR || sampler.mipmapMode() == VK_SAMPLER_MIPMAP_MODE_LINEAR) {
                required |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            }
            if ((format.formatProperties().optimalTilingFeatures() & required) != required) {
                throw new UnsupportedOperationException("Original frame texture format/filtering is unavailable: " + texture.name());
            }
            var query = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default().format(texture.format().vulkan)
                    .type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL).usage(USAGE).flags(texture.kind().flags);
            var properties = VkImageFormatProperties2.calloc(stack).sType$Default();
            int result = VK11.vkGetPhysicalDeviceImageFormatProperties2(gpu.vk().getPhysicalDevice(), query, properties);
            if (result == VK_ERROR_FORMAT_NOT_SUPPORTED) throw new UnsupportedOperationException("Original 2D/cube image is unavailable: " + texture.name());
            if (result != VK_SUCCESS) throw new IllegalStateException("Original frame texture format query failed: " + result);
            var limits = properties.imageFormatProperties();
            if (texture.width() > limits.maxExtent().width() || texture.height() > limits.maxExtent().height()
                    || texture.mips().size() > limits.maxMipLevels() || texture.kind().layers > limits.maxArrayLayers()) {
                throw new UnsupportedOperationException("Original frame texture exceeds image limits: " + texture.name());
            }
        }
    }

    private static void writeDescriptors(GpuDevice gpu, GpuDescriptorRange<GpuDescriptorIndex.Resource> images,
                                         GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplers, List<Upload> uploads,
                                         Map<String, CloudlyVolumeBake.SamplerSpec> samplerSpecs) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var views = VkImageViewCreateInfo.calloc(uploads.size(), stack);
            var descriptors = VkImageDescriptorInfoEXT.calloc(uploads.size(), stack);
            List<GpuDescriptorWriter.ImageWrite> writes = new ArrayList<>();
            for (int index = 0; index < uploads.size(); index++) {
                Upload upload = uploads.get(index);
                TextureSpec texture = upload.spec();
                var view = views.get(index).sType$Default().image(upload.image().image()).viewType(texture.kind().viewType).format(texture.format().vulkan);
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(texture.mips().size())
                        .baseArrayLayer(0).layerCount(texture.kind().layers);
                descriptors.get(index).sType$Default().pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
                writes.add(new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, descriptors.get(index)));
                var sampler = samplerSpecs.get(texture.name());
                gpu.descriptorHeap().writer().writeSampler(samplers, index, VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(sampler.magFilter()).minFilter(sampler.minFilter()).mipmapMode(sampler.mipmapMode())
                        .addressModeU(sampler.addressU()).addressModeV(sampler.addressV()).addressModeW(sampler.addressW())
                        .minLod(sampler.minLod()).maxLod(sampler.maxLod()));
            }
            gpu.descriptorHeap().writer().writeImages(images, 0, writes);
        }
    }

    private static void record(VkCommandBuffer command, List<Upload> uploads) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barriers = VkImageMemoryBarrier2.calloc(uploads.size(), stack);
            for (int index = 0; index < uploads.size(); index++) {
                Upload upload = uploads.get(index);
                var barrier = barriers.get(index).sType$Default().image(upload.image().image())
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK13.VK_ACCESS_2_NONE)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                        .levelCount(upload.spec().mips().size()).baseArrayLayer(0).layerCount(upload.spec().kind().layers);
            }
            VK14.vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            for (Upload upload : uploads) {
                TextureSpec texture = upload.spec();
                var copies = VkBufferImageCopy2.calloc(texture.mips().size() * texture.kind().layers, stack);
                int index = 0;
                for (Mip mip : texture.mips()) for (int layer = 0; layer < texture.kind().layers; layer++) {
                    var copy = copies.get(index++).sType$Default().bufferOffset(Math.addExact(upload.offsets()[mip.level()], Math.multiplyExact(layer, mip.faceBytes())))
                            .bufferRowLength(0).bufferImageHeight(0);
                    copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mip.level()).baseArrayLayer(layer).layerCount(1);
                    copy.imageOffset().set(0, 0, 0);
                    copy.imageExtent().set(mip.width(), mip.height(), 1);
                }
                VK13.vkCmdCopyBufferToImage2(command, VkCopyBufferToImageInfo2.calloc(stack).sType$Default()
                        .srcBuffer(upload.staging().buffer()).dstImage(upload.image().image()).dstImageLayout(VK_IMAGE_LAYOUT_GENERAL).pRegions(copies));
            }
            for (int index = 0; index < uploads.size(); index++) {
                barriers.get(index).srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT).oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL);
            }
            VK14.vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
        }
    }

    static long[] mipOffsets(TextureSpec texture) {
        long[] offsets = new long[texture.mips().size()];
        long offset = 0;
        for (int level = 0; level < offsets.length; level++) {
            offsets[level] = offset;
            offset = Math.addExact(offset, texture.mips().get(level).bytes().remaining());
        }
        return offsets;
    }

    private static GpuComputeCompletion retireStaging(GpuComputeCompletion completion, ResourceLifetime staging) {
        try { staging.close(); return completion; }
        catch (RuntimeException | Error failure) {
            if (completion instanceof GpuComputeCompletion.Failed failed) {
                if (failed.failure() != failure) failed.failure().addSuppressed(failure);
                return completion;
            }
            return new GpuComputeCompletion.Failed(failure);
        }
    }
    private record Upload(TextureSpec spec, VmaImageAllocation image, VmaMappedHostBuffer staging, long[] offsets) { }
}
