package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** Privately compiled source programs and their verified constant-buffer and heap ABI. */
public final class CloudlyShaderLibrary {
    private final Map<String, Program> programs;

    private CloudlyShaderLibrary(Map<String, Program> programs) { this.programs = Map.copyOf(programs); }

    public static CloudlyShaderLibrary load(Path manifest) throws IOException {
        Path actual = manifest.toRealPath();
        JsonObject root = JsonParser.parseString(Files.readString(actual)).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != 1) throw new IOException("Unsupported Cloudly shader library schema");
        Map<String, Program> programs = new LinkedHashMap<>();
        for (JsonElement item : root.getAsJsonArray("programs")) {
            JsonObject entry = item.getAsJsonObject();
            String id = entry.get("id").getAsString();
            Path file = contained(actual.getParent(), entry.get("spirv").getAsString());
            String digest = entry.get("sha256").getAsString();
            byte[] code = verifiedBytes(file, digest);
            int parameterBytes = entry.get("parametersByteSize").getAsInt();
            int bindingBytes = entry.get("bindingByteSize").getAsInt();
            if (parameterBytes <= 0 || (parameterBytes & 3) != 0 || bindingBytes < 0 || (bindingBytes & 3) != 0) {
                throw new IOException("Invalid Cloudly shader buffer sizes: " + id);
            }
            List<PushField> push = entry.getAsJsonArray("pushFields").asList().stream().map(value -> {
                JsonObject field = value.getAsJsonObject();
                return new PushField(field.get("name").getAsString(), field.get("offset").getAsInt(), field.get("size").getAsInt());
            }).toList();
            PushLayout layout = new PushLayout(entry.get("pushByteSize").getAsInt(), push);
            List<BindingField> bindings = entry.has("bindings") ? entry.getAsJsonArray("bindings").asList().stream().map(value -> {
                JsonObject field = value.getAsJsonObject();
                return new BindingField(field.get("name").getAsString(), field.get("heap").getAsString(),
                        field.get("type").getAsString(), field.get("byteOffset").getAsInt());
            }).toList() : List.of();
            Map<String, ParameterField> fields = new LinkedHashMap<>();
            if (entry.has("commonFields")) addFields(fields, "", 0, entry.getAsJsonArray("commonFields").asList());
            if (entry.has("uniformBlocks")) for (JsonElement value : entry.getAsJsonArray("uniformBlocks")) {
                JsonObject block = value.getAsJsonObject();
                addFields(fields, block.get("name").getAsString() + ".", block.get("offset").getAsInt(),
                        block.getAsJsonArray("fields").asList());
            }
            Program program = new Program(id, entry.get("entryPoint").getAsString(), file, digest, code.length,
                    parameterBytes, bindingBytes, layout, new ParameterLayout(parameterBytes, fields),
                    new BindingLayout(bindingBytes, bindings),
                    entry.get("sourceSha256").getAsString());
            if (programs.put(id, program) != null) throw new IOException("Repeated Cloudly shader program: " + id);
        }
        return new CloudlyShaderLibrary(programs);
    }

    public Map<String, Program> programs() { return programs; }

    private static void addFields(Map<String, ParameterField> fields, String prefix, int base, List<JsonElement> entries) {
        for (JsonElement entry : entries) {
            JsonObject field = entry.getAsJsonObject();
            String name = prefix + field.get("name").getAsString();
            ParameterField decoded = new ParameterField(field.get("type").getAsString(),
                    Math.addExact(base, field.get("offset").getAsInt()), field.get("size").getAsInt());
            if (fields.put(name, decoded) != null) throw new IllegalArgumentException("Repeated original parameter " + name);
        }
    }

    public Program program(String id) {
        Program program = programs.get(id);
        if (program == null) throw new IllegalArgumentException("Unknown Cloudly source program " + id);
        return program;
    }

    private static Path contained(Path directory, String relative) throws IOException {
        Path candidate = directory.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !candidate.startsWith(directory)) {
            throw new IOException("Cloudly shader path leaves the private library");
        }
        Path actual = candidate.toRealPath();
        if (!actual.startsWith(directory)) throw new IOException("Cloudly shader link leaves the private library");
        return actual;
    }

    private static byte[] verifiedBytes(Path file, String digest) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(digest)) throw new IOException("Cloudly compiled shader hash mismatch: " + file);
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        if (bytes.length < 20 || (bytes.length & 3) != 0
                || ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() != 0x07230203) {
            throw new IOException("Cloudly source program is not a complete SPIR-V module: " + file);
        }
        return bytes;
    }

    public record PushField(String name, int offset, int size) { }

    public record ParameterField(String type, int offset, int byteSize) { }

    public record BindingField(String name, String heap, String type, int byteOffset) { }

    /** Heap types stay explicit, including resources reachable through inactive source branches. */
    public record BindingLayout(int byteSize, List<BindingField> fields) {
        public BindingLayout {
            fields = List.copyOf(fields);
            var offsets = new java.util.HashSet<Integer>();
            var names = new java.util.HashSet<String>();
            for (BindingField field : fields) {
                if (!names.add(field.name()) || !offsets.add(field.byteOffset()) || field.byteOffset() < 0
                        || (field.byteOffset() & 3) != 0 || field.byteOffset() > byteSize - 4
                        || !(field.heap().equals("Resource") || field.heap().equals("Sampler"))) {
                    throw new IllegalArgumentException("Invalid original heap binding " + field);
                }
            }
        }

        public void write(ByteBuffer target, Map<String, ? extends GpuDescriptorIndex> descriptors) {
            if (target.remaining() != byteSize || descriptors.size() != fields.size()) {
                throw new IllegalArgumentException("Original heap table has the wrong shape");
            }
            ByteBuffer bytes = target.slice().order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = 0; offset < byteSize; offset += 4) bytes.putInt(offset, 0);
            for (BindingField field : fields) {
                GpuDescriptorIndex index = descriptors.get(field.name());
                if (index == null || field.heap().equals("Sampler") != (index instanceof GpuDescriptorIndex.Sampler)) {
                    throw new IllegalArgumentException("Original binding has the wrong descriptor heap: " + field.name());
                }
                bytes.putInt(field.byteOffset(), index.value());
            }
        }
    }

    /** Original byte offsets permit camera patches without recreating the source's uniform structures. */
    public record ParameterLayout(int byteSize, Map<String, ParameterField> fields) {
        public ParameterLayout {
            fields = Map.copyOf(fields);
            for (var entry : fields.entrySet()) {
                ParameterField field = entry.getValue();
                if (field.offset() < 0 || field.byteSize() <= 0 || (field.offset() & 3) != 0
                        || field.offset() > byteSize - field.byteSize()) {
                    throw new IllegalArgumentException("Original parameter leaves its buffer: " + entry.getKey());
                }
            }
        }

        public ParameterField field(String name) {
            ParameterField field = fields.get(name);
            if (field == null) throw new IllegalArgumentException("Unknown original parameter " + name);
            return field;
        }

        public void putFloats(ByteBuffer target, String name, float... values) {
            ParameterField field = field(name);
            if (!field.type().startsWith("float") || field.byteSize() != values.length * 4 || target.remaining() != byteSize) {
                throw new IllegalArgumentException("Wrong original floating-point parameter shape: " + name);
            }
            ByteBuffer bytes = target.slice().order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < values.length; index++) bytes.putFloat(field.offset() + index * 4, values[index]);
        }

        public void putInts(ByteBuffer target, String name, int... values) {
            ParameterField field = field(name);
            if (!(field.type().startsWith("int") || field.type().startsWith("uint"))
                    || field.byteSize() != values.length * 4 || target.remaining() != byteSize) {
                throw new IllegalArgumentException("Wrong original integer parameter shape: " + name);
            }
            ByteBuffer bytes = target.slice().order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < values.length; index++) bytes.putInt(field.offset() + index * 4, values[index]);
        }
    }

    /** Field offsets are emitted from the source ABI and checked against compiled SPIR-V. */
    public record PushLayout(int byteSize, List<PushField> fields) {
        public PushLayout {
            fields = List.copyOf(fields);
            if (byteSize <= 0 || (byteSize & 3) != 0) throw new IllegalArgumentException("Invalid push byte size");
            boolean[] occupied = new boolean[byteSize];
            var names = new java.util.HashSet<String>();
            for (PushField field : fields) {
                if (!names.add(field.name()) || (field.size() != 4 && field.size() != 8)
                        || field.offset() < 0 || field.offset() % field.size() != 0
                        || field.offset() > byteSize - field.size()) throw new IllegalArgumentException("Invalid push field " + field);
                for (int index = field.offset(); index < field.offset() + field.size(); index++) {
                    if (occupied[index]) throw new IllegalArgumentException("Overlapping push field " + field.name());
                    occupied[index] = true;
                }
            }
        }

        /** Writes every declared field; padding belongs to the bridge and is initialized to zero. */
        public void write(ByteBuffer target, Map<String, Long> values) {
            if (target.remaining() != byteSize || values.size() != fields.size()) {
                throw new IllegalArgumentException("Push payload does not match the source ABI");
            }
            ByteBuffer bytes = target.slice().order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < byteSize; index++) bytes.put(index, (byte) 0);
            for (PushField field : fields) {
                Long value = values.get(field.name());
                if (value == null) throw new IllegalArgumentException("Missing push field " + field.name());
                if (field.size() == 8) bytes.putLong(field.offset(), value);
                else bytes.putInt(field.offset(), Math.toIntExact(value));
            }
        }
    }

    public record Program(String id, String entryPoint, Path spirv, String sha256, int spirvBytes,
                          int parametersByteSize, int bindingByteSize, PushLayout pushLayout,
                          ParameterLayout parametersLayout, BindingLayout bindingsLayout, String sourceSha256) {
        public Instance create(GpuDevice gpu, ResourceFactory resources) throws IOException {
            byte[] code = verifiedBytes(spirv, sha256);
            ByteBuffer nativeCode = MemoryUtil.memAlloc(code.length);
            try {
                nativeCode.put(code).flip();
                ShaderObjectCompute shader = ShaderObjectCompute.create(gpu, nativeCode, entryPoint);
                try { return new Instance(this, shader, resources.create(shader::close)); }
                catch (RuntimeException | Error failure) { shader.close(); throw failure; }
            } finally { MemoryUtil.memFree(nativeCode); }
        }
    }

    /** Producer claim on one shader object; jobs and frames retain it through GPU completion. */
    public record Instance(Program program, ShaderObjectCompute shader, ResourceOwner owner) implements AutoCloseable {
        public ResourceOwner retain() { return owner.retain(); }

        public void dispatch(VkCommandBuffer commands, ByteBuffer push, int x, int y, int z) {
            if (push.remaining() != program.pushLayout().byteSize()) throw new IllegalArgumentException("Wrong original push ABI");
            shader.dispatch(commands, push, x, y, z);
        }

        /** Uploads immutable parameter and binding tables shared with asynchronous preparation. */
        public Parameters parameters(GpuDevice gpu, ResourceFactory resources, ByteBuffer constants, ByteBuffer bindings) {
            if (constants.remaining() != program.parametersByteSize() || bindings.remaining() != program.bindingByteSize()) {
                throw new IllegalArgumentException("Original parameter block or heap table has the wrong byte size");
            }
            VmaMappedBuffer buffer = VmaMappedBuffer.createAsync(gpu,
                    (long) constants.remaining() + Math.max(4, bindings.remaining()), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "Cloudly original parameters " + program.id());
            try {
                int constantsSize = constants.remaining();
                buffer.mapped().put(constants.duplicate()).put(bindings.duplicate());
                buffer.flush(0, buffer.byteSize());
                ResourceOwner claim = resources.create(buffer::close);
                return new Parameters(buffer.deviceAddressAt(0).value(), buffer.deviceAddressAt(constantsSize).value(), claim);
            } catch (RuntimeException | Error failure) { buffer.close(); throw failure; }
        }

        @Override public void close() { owner.close(); }
    }

    /** Resources reachable through the table require their own accompanying ownership claims. */
    public record Parameters(long parametersAddress, long bindingsAddress, ResourceOwner owner) implements AutoCloseable {
        public ResourceOwner retain() { return owner.retain(); }
        @Override public void close() { owner.close(); }
    }
}
