package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.Set;

/** Private source parameter recipes preserve native evaluation order and publish dependent aliases immediately. */
public final class CloudlyFrameRecipe {
    private final boolean executionReady;
    private final Map<String, double[]> sourceInputs;
    private final List<Derived> derived;
    private final List<UniformPatch> uniforms;
    private final Map<String, Template> templates;

    private CloudlyFrameRecipe(boolean executionReady, Map<String, double[]> sourceInputs,
                                List<Derived> derived, List<UniformPatch> uniforms, Map<String, Template> templates) {
        this.executionReady = executionReady;
        this.sourceInputs = Map.copyOf(sourceInputs);
        this.derived = List.copyOf(derived);
        this.uniforms = List.copyOf(uniforms);
        this.templates = Map.copyOf(templates);
    }

    /** Parameter inspection is available before readiness; GPU callers must require execution readiness separately. */
    public static CloudlyFrameRecipe load(Path manifest, CloudlyShaderLibrary library,
                                           Map<String, String> runtimeProgramIds) throws IOException {
        Path actual = manifest.toRealPath();
        JsonObject root = JsonParser.parseString(Files.readString(actual)).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != 1) throw new IOException("Unsupported original frame recipe schema");
        Map<String, double[]> inputs = new LinkedHashMap<>();
        for (String section : List.of("sourceInputs", "hostSelections")) {
            root.getAsJsonObject(section).entrySet().forEach(entry ->
                    inputs.put(entry.getKey(), CloudlyUniformExpressions.evaluate(entry.getValue(), Map.of())));
        }
        List<Derived> derived = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("derivedInputs")) {
            JsonObject item = value.getAsJsonObject();
            derived.add(new Derived(item.get("name").getAsString(), item.get("expression").deepCopy()));
        }
        List<UniformPatch> uniforms = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("uniformPatches")) {
            JsonObject item = value.getAsJsonObject();
            uniforms.add(new UniformPatch(item.get("uniform").getAsString(), item.get("field").getAsString(),
                    item.get("type").getAsString(), item.get("offset").getAsInt(), item.get("expression").deepCopy(),
                    item.getAsJsonArray("outputAliases").asList().stream().map(JsonElement::getAsString).toList()));
        }
        List<UniformPatch> inactive = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("inactiveUniformMembers")) {
            JsonObject item = value.getAsJsonObject();
            inactive.add(new UniformPatch(item.get("uniform").getAsString(), item.get("field").getAsString(),
                    item.get("type").getAsString(), item.get("offset").getAsInt(), item.get("value").deepCopy(), List.of()));
        }
        Map<String, Template> templates = new LinkedHashMap<>();
        for (JsonElement value : root.getAsJsonArray("programs")) {
            JsonObject item = value.getAsJsonObject();
            String id = item.get("programId").getAsString();
            String runtimeId = runtimeProgramIds.get(id);
            if (runtimeId == null) throw new IOException("Missing original-to-host program selection " + id);
            var program = library.program(runtimeId);
            JsonObject payload = item.getAsJsonObject("template");
            byte[] bytes = payload(actual.getParent(), payload.get("path").getAsString(), payload.get("sha256").getAsString());
            if (bytes.length != program.parametersByteSize() || item.get("parametersByteSize").getAsInt() != bytes.length) {
                throw new IOException("Original frame template has the wrong byte size: " + id);
            }
            Map<String, Integer> blocks = new LinkedHashMap<>();
            for (JsonElement blockValue : item.getAsJsonArray("uniformBlocks")) {
                JsonObject block = blockValue.getAsJsonObject();
                blocks.put(block.get("name").getAsString(), block.get("offset").getAsInt());
            }
            for (UniformPatch patch : uniforms) validateUniform(program, blocks, patch);
            for (UniformPatch patch : inactive) validateUniform(program, blocks, patch);
            List<CommonPatch> common = new ArrayList<>();
            for (JsonElement commonValue : item.getAsJsonArray("commonFields")) {
                JsonObject field = commonValue.getAsJsonObject();
                String name = field.get("name").getAsString();
                var layoutField = program.parametersLayout().field(name);
                if (!layoutField.type().equals(field.get("type").getAsString())
                        || layoutField.offset() != field.get("offset").getAsInt()
                        || layoutField.byteSize() != field.get("size").getAsInt()) {
                    throw new IOException("Original frame field ABI differs: " + id + "." + name);
                }
                common.add(new CommonPatch(name, field.get("expression").deepCopy()));
            }
            if (templates.put(id, new Template(program, bytes, Map.copyOf(blocks), List.copyOf(common), List.copyOf(inactive))) != null) {
                throw new IOException("Repeated original frame template " + id);
            }
        }
        return new CloudlyFrameRecipe(root.get("executionReady").getAsBoolean(), inputs, derived, uniforms, templates);
    }

    public boolean executionReady() { return executionReady; }

    public void requireExecutionReady() {
        if (!executionReady) throw new IllegalStateException("Original frame recipe still has unresolved producers or stages");
    }

    public Set<String> runtimeProgramIds() {
        return templates.values().stream().map(template -> template.program().id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Frozen authored constants are cloned for every frame; a missing executed input remains an error. */
    public EncodedFrame encode(Map<String, double[]> frameInputs, ViewWriter viewWriter) {
        return encode(templates.keySet(), frameInputs, viewWriter);
    }

    /** A stage only requires its own common producers; native uniform patches still evaluate in source order. */
    public EncodedFrame encode(Set<String> sourceProgramIds, Map<String, double[]> frameInputs, ViewWriter viewWriter) {
        Map<String, double[]> inputs = new LinkedHashMap<>();
        sourceInputs.forEach((name, value) -> inputs.put(name, value.clone()));
        frameInputs.forEach((name, value) -> inputs.put(name, value.clone()));
        Map<String, JsonElement> derivedExpressions = new LinkedHashMap<>();
        for (Derived input : derived) derivedExpressions.put(input.name(), input.expression());
        class Resolver {
            final Set<String> resolving = new java.util.HashSet<>();
            double[] resolve(String name) {
                double[] value = inputs.get(name);
                if (value != null) return value;
                JsonElement expression = derivedExpressions.get(name);
                if (expression == null) return null;
                if (!resolving.add(name)) throw new IllegalArgumentException("Cyclic original derived input " + name);
                try {
                    value = CloudlyUniformExpressions.evaluate(expression, this::resolve);
                    inputs.put(name, value);
                    return value;
                } finally { resolving.remove(name); }
            }
        }
        Resolver resolver = new Resolver();
        Set<String> consumedUniforms = new java.util.HashSet<>();
        for (String id : sourceProgramIds) {
            consumedUniforms.addAll(java.util.Objects.requireNonNull(templates.get(id), "Unknown source frame program " + id).blocks().keySet());
        }
        Map<UniformPatch, double[]> values = new LinkedHashMap<>();
        for (UniformPatch patch : uniforms) {
            if (!consumedUniforms.contains(patch.uniform())) continue;
            double[] result = CloudlyUniformExpressions.evaluate(patch.expression(), resolver::resolve);
            values.put(patch, result);
            for (String alias : patch.aliases()) inputs.put(alias, result.clone());
        }
        Map<String, ByteBuffer> encoded = new LinkedHashMap<>();
        Map<String, String> runtimeIds = new LinkedHashMap<>();
        for (String id : sourceProgramIds) {
            Template template = java.util.Objects.requireNonNull(templates.get(id), "Unknown source frame program " + id);
            ByteBuffer bytes = ByteBuffer.wrap(template.bytes().clone()).order(ByteOrder.LITTLE_ENDIAN);
            var layout = template.program().parametersLayout();
            for (var patchValue : values.entrySet()) {
                UniformPatch patch = patchValue.getKey();
                if (template.blocks().containsKey(patch.uniform())) write(layout, bytes, patch.name(), patchValue.getValue());
            }
            for (UniformPatch patch : template.inactive()) {
                if (template.blocks().containsKey(patch.uniform())) write(layout, bytes, patch.name(), CloudlyUniformExpressions.evaluate(patch.expression(), resolver::resolve));
            }
            for (CommonPatch patch : template.common()) write(layout, bytes, patch.name(), CloudlyUniformExpressions.evaluate(patch.expression(), resolver::resolve));
            if (template.blocks().containsKey("View")) viewWriter.write(layout, bytes);
            encoded.put(id, bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
            runtimeIds.put(id, template.program().id());
        }
        return new EncodedFrame(Map.copyOf(encoded), Map.copyOf(runtimeIds));
    }

    private static void validateUniform(CloudlyShaderLibrary.Program program, Map<String, Integer> blocks,
                                         UniformPatch patch) throws IOException {
        Integer base = blocks.get(patch.uniform());
        if (base == null) return;
        var field = program.parametersLayout().field(patch.name());
        if (!field.type().equals(patch.type()) || field.offset() != base + patch.offset()) {
            throw new IOException("Original uniform patch ABI differs: " + program.id() + "." + patch.name());
        }
    }

    private static void write(CloudlyShaderLibrary.ParameterLayout layout, ByteBuffer bytes, String name, double[] values) {
        var field = layout.field(name);
        if (field.type().startsWith("float")) {
            float[] converted = new float[values.length];
            for (int index = 0; index < values.length; index++) converted[index] = (float)values[index];
            layout.putFloats(bytes, name, converted);
        } else if (field.type().startsWith("uint") || field.type().startsWith("int")) {
            int[] converted = new int[values.length];
            for (int index = 0; index < values.length; index++) {
                double number = values[index];
                long integer = (long)number;
                if (number != integer || integer < Integer.MIN_VALUE || integer > 0xffffffffL
                        || field.type().startsWith("int") && integer > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Original integer input is outside its 32-bit range: " + name);
                }
                converted[index] = (int)integer;
            }
            layout.putInts(bytes, name, converted);
        } else throw new IllegalArgumentException("Unsupported original parameter type " + field.type());
    }

    private static byte[] payload(Path directory, String relative, String digest) throws IOException {
        Path candidate = directory.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !candidate.startsWith(directory)) throw new IOException("Original frame payload leaves its private directory");
        Path actual = candidate.toRealPath();
        if (!actual.startsWith(directory)) throw new IOException("Original frame payload link leaves its private directory");
        byte[] bytes = Files.readAllBytes(actual);
        try {
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(digest)) {
                throw new IOException("Original frame template hash changed: " + relative);
            }
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        return bytes;
    }

    @FunctionalInterface public interface ViewWriter {
        void write(CloudlyShaderLibrary.ParameterLayout layout, ByteBuffer parameters);
    }

    public record EncodedFrame(Map<String, ByteBuffer> parameters, Map<String, String> runtimeIds) {
        public EncodedFrame {
            parameters = Map.copyOf(parameters);
            runtimeIds = Map.copyOf(runtimeIds);
        }

        public ByteBuffer parameters(String sourceProgramId) {
            return java.util.Objects.requireNonNull(parameters.get(sourceProgramId), "Unknown source frame program " + sourceProgramId)
                    .duplicate().order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private record Derived(String name, JsonElement expression) { }
    private record UniformPatch(String uniform, String field, String type, int offset, JsonElement expression, List<String> aliases) {
        String name() { return uniform + "." + field; }
    }
    private record CommonPatch(String name, JsonElement expression) { }
    private record Template(CloudlyShaderLibrary.Program program, byte[] bytes, Map<String, Integer> blocks,
                             List<CommonPatch> common, List<UniformPatch> inactive) { }
}
