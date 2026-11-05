package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;

/** Captured private input metadata connecting uploaded source volumes to one asynchronous bake. */
public final class CloudlyOriginalBakeResources {
    private final CloudlyShaderLibrary library;
    private final CloudlyBakePlan plan;
    private final List<Input> inputs;
    private final Map<String, String> outputBindings;

    private CloudlyOriginalBakeResources(CloudlyShaderLibrary library, CloudlyBakePlan plan, List<Input> inputs, Map<String, String> outputBindings) {
        this.library = library;
        this.plan = plan;
        this.inputs = List.copyOf(inputs);
        this.outputBindings = Map.copyOf(outputBindings);
    }

    /** Reads and hashes structured payloads before GPU allocation; the captured recipe is immutable. */
    public static CloudlyOriginalBakeResources load(Path path, CloudlyShaderLibrary library) throws IOException {
        Path actual = path.toRealPath();
        JsonObject root = JsonParser.parseString(Files.readString(actual)).getAsJsonObject();
        CloudlyBakePlan plan = CloudlyBakePlan.load(actual, library);
        List<Input> inputs = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("externalInputs")) {
            JsonObject input = element.getAsJsonObject();
            String name = input.get("name").getAsString();
            switch (input.get("kind").getAsString()) {
                case "sampledImage3D" -> inputs.add(new Volume(name, input.get("sourceTextureId").getAsInt(),
                        input.get("width").getAsInt(), input.get("height").getAsInt(), input.get("depth").getAsInt(),
                        input.get("sha256").getAsString()));
                case "structuredBufferDescriptor" -> {
                    int stride = input.get("stride").getAsInt();
                    int count = input.get("elementCount").getAsInt();
                    byte[] bytes = payload(actual.getParent(), input.get("path").getAsString(), input.get("sha256").getAsString());
                    if (stride <= 0 || count <= 0 || bytes.length != Math.multiplyExact(stride, count)
                            || bytes.length != input.get("byteSize").getAsInt()) {
                        throw new IOException("Private bake structured payload has inconsistent elements: " + name);
                    }
                    inputs.add(new Structured(name, stride, bytes));
                }
                case "uniformFloat4TexelBufferDescriptor" -> {
                    byte[] bytes = payload(actual.getParent(), input.get("path").getAsString(), input.get("sha256").getAsString());
                    if (input.get("format").getAsInt() != VK_FORMAT_R32G32B32A32_SFLOAT || input.get("stride").getAsInt() != 16
                            || bytes.length != Math.multiplyExact(input.get("elementCount").getAsInt(), 16)
                            || bytes.length != input.get("byteSize").getAsInt()) {
                        throw new IOException("Private bake float4 texel payload has inconsistent elements: " + name);
                    }
                    inputs.add(new Float4Texels(name, bytes));
                }
                case "storageTexelBufferDescriptor" -> {
                    int channels = input.get("channels").getAsInt();
                    int bytes = input.get("byteSize").getAsInt();
                    int reservedChannels = bytes / 8;
                    if (input.get("format").getAsInt() != VK_FORMAT_R32_UINT || bytes % 8 != 0
                            || channels < 1 || channels > reservedChannels || reservedChannels > 4) {
                        throw new IOException("Private bake statistics require uint min/max channel pairs: " + name);
                    }
                    List<JsonElement> initial = input.getAsJsonArray("initialWords").asList();
                    if (initial.size() != reservedChannels * 2) throw new IOException("Private bake statistics initialization size: " + name);
                    for (int index = 0; index < initial.size(); index++) {
                        if (initial.get(index).getAsLong() != (index % 2 == 0 ? 0xffffffffL : 0)) {
                            throw new IOException("Private bake statistics initialization does not match its reduction: " + name);
                        }
                    }
                    inputs.add(new MinMax(name, input.get("sourceTexture").getAsString(), channels, reservedChannels,
                            input.get("unusedCpuFloat4Value").getAsFloat()));
                }
                case "bufferAddress" -> {
                    int bytes = input.get("byteSize").getAsInt();
                    List<JsonElement> initial = input.getAsJsonArray("initialWords").asList();
                    if (bytes <= 0 || bytes != initial.size() * 4) throw new IOException("Private bake address initialization size: " + name);
                    ByteBuffer words = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    for (JsonElement word : initial) words.putInt((int) word.getAsLong());
                    inputs.add(new Address(name, words.array()));
                }
                default -> throw new IOException("Unknown private bake input kind: " + input.get("kind").getAsString());
            }
        }
        Map<String, String> outputBindings = new LinkedHashMap<>();
        for (var entry : root.getAsJsonObject("outputBindings").entrySet()) {
            String image = entry.getValue().getAsString();
            if (plan.images().stream().noneMatch(spec -> spec.name().equals(image) && spec.publish())) {
                throw new IOException("Private bake output is not published: " + image);
            }
            outputBindings.put(entry.getKey(), image);
        }
        return new CloudlyOriginalBakeResources(library, plan, inputs, outputBindings);
    }

    /** Borrowed descriptors from an uploaded and hash-validated source revision. Retain that revision while in use. */
    public Map<String, GpuDescriptorIndex.Resource> sourceDescriptors(CloudlyCloudResources uploadedSource) throws IOException {
        Map<String, GpuDescriptorIndex.Resource> descriptors = new LinkedHashMap<>();
        for (Input input : inputs) if (input instanceof Volume volume) {
            var source = uploadedSource.source().textures().stream().filter(texture -> texture.textureId() == volume.textureId())
                    .findFirst().orElseThrow(() -> new IOException("Private bake source texture is absent: " + volume.textureId()));
            var mip = source.mips().getFirst();
            var uploaded = uploadedSource.texture(volume.textureId());
            if (!mip.sha256().equals(volume.sha256()) || mip.width() != volume.width() || mip.height() != volume.height()
                    || mip.depth() != volume.depth() || uploaded.width() != volume.width() || uploaded.height() != volume.height()
                    || uploaded.depth() != volume.depth() || uploaded.mipLevels() != source.mips().size()) {
                throw new IOException("Private bake source volume does not match the uploaded revision: " + volume.name());
            }
            descriptors.put(volume.name(), uploaded.sampledIndex());
        }
        return Map.copyOf(descriptors);
    }

    /**
     * Allocates structured inputs and readback state, then submits the complete bake once. The source
     * revision is borrowed. Independent input claims remain alive through the completion callback.
     * Ready transfers completed output ownership and immutable channel statistics to the recipient.
     */
    public GpuComputeJob prepare(GpuDevice gpu, GpuComputeQueue queue, ResourceFactory resources,
                                 CloudlyCloudResources uploadedSource,
                                 Consumer<? super Completion> completion) throws IOException {
        return prepareInputs(gpu, queue, resources, uploadedSource, completion);
    }

    /** Submits a self-contained bake whose private inputs require no uploaded source volumes. */
    public GpuComputeJob prepare(GpuDevice gpu, GpuComputeQueue queue, ResourceFactory resources,
                                 Consumer<? super Completion> completion) throws IOException {
        if (inputs.stream().anyMatch(input -> input instanceof Volume)) {
            throw new IllegalArgumentException("This private bake requires an uploaded source volume revision");
        }
        return prepareInputs(gpu, queue, resources, null, completion);
    }

    private GpuComputeJob prepareInputs(GpuDevice gpu, GpuComputeQueue queue, ResourceFactory resources,
                                        CloudlyCloudResources uploadedSource,
                                        Consumer<? super Completion> completion) throws IOException {
        List<ResourceOwner> claims = new ArrayList<>();
        Map<String, GpuDescriptorIndex> descriptors = new LinkedHashMap<>();
        Map<String, Long> addresses = new LinkedHashMap<>();
        Map<MinMax, CloudlyStatisticsBuffer> statistics = new LinkedHashMap<>();
        try {
            if (uploadedSource != null) {
                claims.add(uploadedSource.retain());
                descriptors.putAll(sourceDescriptors(uploadedSource));
            }
            for (Input input : inputs) {
                if (input instanceof Structured structured) {
                    var buffer = CloudlySourceBuffer.upload(gpu, resources, ByteBuffer.wrap(structured.bytes()), structured.stride(), List.of());
                    claims.add(buffer.owner());
                    descriptors.put(structured.name(), buffer.descriptor());
                } else if (input instanceof Float4Texels texels) {
                    var buffer = CloudlySourceBuffer.uploadFloat4Texels(gpu, resources, ByteBuffer.wrap(texels.bytes()), List.of());
                    claims.add(buffer.owner());
                    descriptors.put(texels.name(), buffer.descriptor());
                } else if (input instanceof MinMax minMax) {
                    var buffer = CloudlyStatisticsBuffer.create(gpu, resources, minMax.reservedChannels());
                    claims.add(buffer.owner());
                    descriptors.put(minMax.name(), buffer.descriptor());
                    statistics.put(minMax, buffer);
                } else if (input instanceof Address address) {
                    var buffer = CloudlySourceBuffer.upload(gpu, resources, ByteBuffer.wrap(address.bytes()), Integer.BYTES, List.of());
                    claims.add(buffer.owner());
                    addresses.put(address.name(), buffer.deviceRange().address().value());
                }
            }
            return CloudlyVolumeBake.prepare(gpu, queue, resources, library, plan.bind(descriptors, addresses, claims), result -> {
                Completion adapted;
                try {
                    if (result instanceof CloudlyVolumeBake.Ready ready) {
                        Map<String, Statistics> values = new LinkedHashMap<>();
                        statistics.forEach((spec, buffer) -> values.put(spec.sourceTexture(), spec.readCompleted(buffer)));
                        adapted = new Ready(ready, outputBindings, values);
                    } else if (result instanceof CloudlyVolumeBake.Failed failed) adapted = new Failed(failed.failure());
                    else adapted = new Cancelled();
                    closeClaims(claims);
                } catch (RuntimeException | Error failure) {
                    ResourceLifetime.closeAfterFailure(failure, () -> {
                        if (result instanceof CloudlyVolumeBake.Ready ready) ready.close();
                    }, () -> closeClaims(claims));
                    adapted = new Failed(failure);
                }
                completion.accept(adapted);
            });
        } catch (IOException | RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, () -> closeClaims(claims));
            throw failure;
        }
    }

    public CloudlyBakePlan plan() { return plan; }
    public Map<String, String> outputBindings() { return outputBindings; }
    public Map<String, Integer> statisticsChannels() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Input input : inputs) if (input instanceof MinMax statistics) result.put(statistics.sourceTexture(), statistics.channels());
        return Map.copyOf(result);
    }

    public sealed interface Completion permits Ready, Failed, Cancelled { }
    public record Failed(Throwable failure) implements Completion { }
    public record Cancelled() implements Completion { }

    public record Ready(CloudlyVolumeBake.Ready volumes, Map<String, String> outputBindings,
                        Map<String, Statistics> statistics) implements Completion, AutoCloseable {
        public Ready { outputBindings = Map.copyOf(outputBindings); statistics = Map.copyOf(statistics); }
        public CloudlyVolumeBake.View outputs() { return volumes.outputs(); }
        public CloudlyVolumeBake.Texture texture(String binding) {
            String image = outputBindings.get(binding);
            if (image == null) throw new IllegalArgumentException("Unknown private bake output binding " + binding);
            return outputs().image(image);
        }
        public ResourceOwner retain() { return volumes.retain(); }
        @Override public void close() { volumes.close(); }
    }

    public record Statistics(int channels, Float4 minimum, Float4 maximum) {
        /** Converts only generated channels; unused CPU channels retain the recipe's callback padding. */
        public static Statistics fromPairs(int channels, float padding, float[] pairs) {
            if (channels < 1 || channels > 4 || pairs.length < channels * 2) {
                throw new IllegalArgumentException("Statistics need complete min/max pairs for one to four channels");
            }
            float[] minimum = new float[4], maximum = new float[4];
            Arrays.fill(minimum, padding);
            Arrays.fill(maximum, padding);
            for (int channel = 0; channel < channels; channel++) {
                minimum[channel] = pairs[channel * 2];
                maximum[channel] = pairs[channel * 2 + 1];
            }
            return new Statistics(channels, new Float4(minimum[0], minimum[1], minimum[2], minimum[3]),
                    new Float4(maximum[0], maximum[1], maximum[2], maximum[3]));
        }
    }
    public record Float4(float x, float y, float z, float w) {
        public float[] toArray() { return new float[]{x, y, z, w}; }
        public double[] toDoubles() { return new double[]{x, y, z, w}; }
    }

    private sealed interface Input permits Volume, Structured, Float4Texels, MinMax, Address { }
    private record Volume(String name, int textureId, int width, int height, int depth, String sha256) implements Input { }
    private record Structured(String name, int stride, byte[] bytes) implements Input { }
    private record Float4Texels(String name, byte[] bytes) implements Input { }
    private record Address(String name, byte[] bytes) implements Input { }
    private record MinMax(String name, String sourceTexture, int channels, int reservedChannels, float padding) implements Input {
        Statistics readCompleted(CloudlyStatisticsBuffer buffer) {
            return Statistics.fromPairs(channels, padding, buffer.readCompleted());
        }
    }

    private static void closeClaims(List<ResourceOwner> claims) {
        new ResourceLifetime(claims.stream().<Runnable>map(claim -> claim::close).toArray(Runnable[]::new)).close();
    }

    private static byte[] payload(Path directory, String relative, String digest) throws IOException {
        Path resolved = directory.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !resolved.startsWith(directory)) throw new IOException("Private bake input leaves its recipe directory");
        Path actual = resolved.toRealPath();
        if (!actual.startsWith(directory)) throw new IOException("Private bake input link leaves its recipe directory");
        byte[] bytes = Files.readAllBytes(actual);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(digest)) throw new IOException("Private bake input hash changed: " + relative);
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        return bytes;
    }
}
