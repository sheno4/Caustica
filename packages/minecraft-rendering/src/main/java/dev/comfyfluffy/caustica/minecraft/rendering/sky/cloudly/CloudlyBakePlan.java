package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit private dispatch recipes; missing native inputs cannot silently become zero constants. */
public final class CloudlyBakePlan {
    private final List<CloudlyVolumeBake.ImageSpec> images;
    private final List<CloudlyVolumeBake.SamplerSpec> samplers;
    private final List<Stage> stages;

    private CloudlyBakePlan(List<CloudlyVolumeBake.ImageSpec> images, List<CloudlyVolumeBake.SamplerSpec> samplers,
                            List<Stage> stages) {
        this.images = List.copyOf(images);
        this.samplers = List.copyOf(samplers);
        this.stages = List.copyOf(stages);
    }

    public static CloudlyBakePlan load(Path path, CloudlyShaderLibrary library) throws IOException {
        Path actual = path.toRealPath();
        JsonObject root = JsonParser.parseString(Files.readString(actual)).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != 1 || !root.get("executionReady").getAsBoolean()) {
            throw new IOException("Original Cloudly bake recipe has unresolved execution inputs");
        }
        List<CloudlyVolumeBake.ImageSpec> images = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("images")) {
            JsonObject image = value.getAsJsonObject();
            int[] clear = image.getAsJsonArray("clearWords").asList().stream().mapToInt(JsonElement::getAsInt).toArray();
            if (clear.length != 4) throw new IOException("Original bake clear needs four words");
            images.add(new CloudlyVolumeBake.ImageSpec(image.get("name").getAsString(),
                    CloudlyVolumeBake.Dimension.valueOf(image.get("dimension").getAsString()),
                    image.get("width").getAsInt(), image.get("height").getAsInt(), image.get("depth").getAsInt(),
                    image.get("mipLevels").getAsInt(), image.get("format").getAsInt(),
                    image.get("linearFiltering").getAsBoolean(), image.get("publish").getAsBoolean(),
                    new CloudlyVolumeBake.ClearValue(clear[0], clear[1], clear[2], clear[3])));
        }
        List<CloudlyVolumeBake.SamplerSpec> samplers = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("samplers")) {
            JsonObject sampler = value.getAsJsonObject();
            samplers.add(new CloudlyVolumeBake.SamplerSpec(sampler.get("name").getAsString(),
                    sampler.get("magFilter").getAsInt(), sampler.get("minFilter").getAsInt(),
                    sampler.get("mipmapMode").getAsInt(), sampler.get("addressU").getAsInt(),
                    sampler.get("addressV").getAsInt(), sampler.get("addressW").getAsInt(),
                    sampler.get("minLod").getAsFloat(), sampler.get("maxLod").getAsFloat()));
        }
        List<Stage> stages = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray(root.has("stages") ? "stages" : "dispatches")) {
            JsonObject dispatch = value.getAsJsonObject();
            if (dispatch.has("kind") && dispatch.get("kind").getAsString().equals("copy")) {
                stages.add(new Copy(new CloudlyVolumeBake.CopySpec(dispatch.get("source").getAsString(),
                        dispatch.get("sourceMip").getAsInt(), dispatch.get("destination").getAsString(), dispatch.get("destinationMip").getAsInt())));
                continue;
            }
            var program = library.program(dispatch.get("programId").getAsString());
            JsonObject constants = dispatch.getAsJsonObject("constants");
            byte[] bytes = payload(actual.getParent(), constants.get("path").getAsString(), constants.get("sha256").getAsString());
            if (bytes.length != program.parametersByteSize()) throw new IOException("Original bake constant buffer has wrong size: " + program.id());
            Map<String, Reference> bindings = references(dispatch.getAsJsonObject("bindings"));
            Map<String, Reference> push = references(dispatch.getAsJsonObject("push"));
            int[] groups = dispatch.getAsJsonArray("groups").asList().stream().mapToInt(JsonElement::getAsInt).toArray();
            if (groups.length != 3 || groups[0] < 1 || groups[1] < 1 || groups[2] < 1) throw new IOException("Original bake dispatch needs three positive group counts");
            if (!bindings.keySet().equals(program.bindingsLayout().fields().stream().map(CloudlyShaderLibrary.BindingField::name)
                    .collect(java.util.stream.Collectors.toSet()))
                    || !push.keySet().equals(program.pushLayout().fields().stream().map(CloudlyShaderLibrary.PushField::name)
                    .collect(java.util.stream.Collectors.toSet()))) {
                throw new IOException("Original recipe does not supply the complete program ABI: " + program.id());
            }
            stages.add(new Dispatch(program, bytes, bindings, push, groups[0], groups[1], groups[2]));
        }
        return new CloudlyBakePlan(images, samplers, stages);
    }

    /** Borrowed external GPU inputs require explicit claims alongside their descriptors and addresses. */
    public CloudlyVolumeBake.Plan bind(Map<String, ? extends GpuDescriptorIndex> externalDescriptors,
                                       Map<String, Long> externalAddresses, List<ResourceOwner> inputs) {
        Map<String, GpuDescriptorIndex> descriptors = Map.copyOf(externalDescriptors);
        Map<String, Long> addresses = Map.copyOf(externalAddresses);
        List<CloudlyVolumeBake.StageSpec> bound = stages.stream().<CloudlyVolumeBake.StageSpec>map(stage -> {
            if (stage instanceof Copy copy) return copy.spec();
            Dispatch dispatch = (Dispatch) stage;
            return new CloudlyVolumeBake.DispatchSpec(dispatch.program().id(), ByteBuffer.wrap(dispatch.constants()), view -> {
                    Map<String, GpuDescriptorIndex> table = new LinkedHashMap<>();
                    dispatch.bindings().forEach((name, reference) -> table.put(name, reference.descriptor(view, descriptors)));
                    ByteBuffer bytes = ByteBuffer.allocate(dispatch.program().bindingByteSize()).order(ByteOrder.LITTLE_ENDIAN);
                    dispatch.program().bindingsLayout().write(bytes, table);
                    return bytes;
                }, (view, parameters) -> {
                    Map<String, Long> push = new LinkedHashMap<>();
                    dispatch.push().forEach((name, reference) -> push.put(name, reference.value(view, parameters, descriptors, addresses)));
                    return push;
                }, dispatch.x(), dispatch.y(), dispatch.z());
        }).toList();
        return new CloudlyVolumeBake.Plan(images, samplers, bound, inputs);
    }

    public List<CloudlyVolumeBake.ImageSpec> images() { return images; }
    public List<CloudlyVolumeBake.SamplerSpec> samplers() { return samplers; }
    public int dispatchCount() { return (int) stages.stream().filter(stage -> stage instanceof Dispatch).count(); }
    public int stageCount() { return stages.size(); }

    private static Map<String, Reference> references(JsonObject object) {
        Map<String, Reference> result = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> {
            JsonObject value = entry.getValue().getAsJsonObject();
            String kind = value.get("kind").getAsString();
            String name = value.has("name") ? value.get("name").getAsString() : "";
            int mip = value.has("mip") ? value.get("mip").getAsInt() : kind.equals("sampledImage") ? -1 : 0;
            long literal = value.has("value") ? value.get("value").getAsLong() : 0;
            result.put(entry.getKey(), new Reference(kind, name, mip, literal));
        });
        return Map.copyOf(result);
    }

    private static byte[] payload(Path directory, String relative, String digest) throws IOException {
        Path resolved = directory.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !resolved.startsWith(directory)) throw new IOException("Original recipe payload leaves its private directory");
        Path actual = resolved.toRealPath();
        if (!actual.startsWith(directory)) throw new IOException("Original recipe payload link leaves its private directory");
        byte[] bytes = Files.readAllBytes(actual);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(digest)) throw new IOException("Original recipe parameter hash changed: " + relative);
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        return bytes;
    }

    private sealed interface Stage permits Dispatch, Copy { }
    private record Copy(CloudlyVolumeBake.CopySpec spec) implements Stage { }
    private record Dispatch(CloudlyShaderLibrary.Program program, byte[] constants, Map<String, Reference> bindings,
                             Map<String, Reference> push, int x, int y, int z) implements Stage { }

    private record Reference(String kind, String name, int mip, long literal) {
        GpuDescriptorIndex descriptor(CloudlyVolumeBake.View view, Map<String, GpuDescriptorIndex> external) {
            return switch (kind) {
                case "sampledImage" -> view.image(name).sampledIndex(mip);
                case "storageImage" -> view.image(name).storageIndex(mip);
                case "sampler" -> view.sampler(name);
                case "externalDescriptor" -> java.util.Objects.requireNonNull(external.get(name), "Missing original input descriptor " + name);
                default -> throw new IllegalArgumentException("Unknown original descriptor reference " + kind);
            };
        }

        long value(CloudlyVolumeBake.View view, CloudlyShaderLibrary.Parameters parameters,
                   Map<String, GpuDescriptorIndex> descriptors, Map<String, Long> addresses) {
            return switch (kind) {
                case "parametersAddress" -> parameters.parametersAddress();
                case "bindingsAddress" -> parameters.bindingsAddress();
                case "externalAddress" -> java.util.Objects.requireNonNull(addresses.get(name), "Missing original input address " + name);
                case "literal" -> literal;
                default -> descriptor(view, descriptors).value();
            };
        }
    }
}
