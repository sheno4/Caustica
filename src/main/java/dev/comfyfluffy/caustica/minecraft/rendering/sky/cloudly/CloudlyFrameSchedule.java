package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CPU-only frame graph inspection and binding. Resource allocation and submission remain explicit host work. */
public final class CloudlyFrameSchedule {
    private final boolean executionReady;
    private final List<Resource> resources;
    private final List<Stage> stages;
    private final Map<String, Stage> byName;

    private CloudlyFrameSchedule(boolean executionReady, List<Resource> resources, List<Stage> stages) {
        this.executionReady = executionReady;
        this.resources = List.copyOf(resources);
        this.stages = List.copyOf(stages);
        Map<String, Stage> index = new LinkedHashMap<>();
        for (Stage stage : stages) index.put(stage.name(), stage);
        byName = Map.copyOf(index);
    }

    /** An incomplete graph can be inspected, but its readiness cannot be inferred from successful decoding. */
    public static CloudlyFrameSchedule load(Path manifest, CloudlyShaderLibrary library) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(manifest.toRealPath())).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != 1) throw new IOException("Unsupported source frame schedule schema");
        List<Resource> resources = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String section : List.of("images", "buffers", "externalDescriptors")) {
            for (JsonElement entry : root.getAsJsonArray(section)) {
                JsonObject value = entry.getAsJsonObject();
                String name = value.get("name").getAsString();
                if (!names.add(name)) throw new IOException("Repeated frame resource " + name);
                Kind kind = switch (section) {
                    case "images" -> Kind.IMAGE;
                    case "buffers" -> Kind.BUFFER;
                    default -> Kind.EXTERNAL;
                };
                resources.add(new Resource(name, kind, value.deepCopy()));
            }
        }
        for (var entry : root.getAsJsonObject("samplers").entrySet()) {
            if (!names.add(entry.getKey())) throw new IOException("Repeated frame sampler " + entry.getKey());
            resources.add(new Resource(entry.getKey(), Kind.SAMPLER, entry.getValue().getAsJsonObject().deepCopy()));
        }
        List<Stage> stages = new ArrayList<>();
        Map<String, Resource> resourceIndex = new LinkedHashMap<>();
        for (Resource resource : resources) resourceIndex.put(resource.name(), resource);
        Set<String> preceding = new HashSet<>();
        for (JsonElement entry : root.getAsJsonArray("stages")) {
            JsonObject value = entry.getAsJsonObject();
            String name = value.get("name").getAsString();
            if (preceding.contains(name)) throw new IOException("Repeated frame stage " + name);
            List<String> dependencies = strings(value.getAsJsonArray("dependsOn"));
            if (!preceding.containsAll(dependencies)) throw new IOException("Frame dependencies are missing or unordered: " + name);
            String operation = value.get("operation").getAsString();
            CloudlyShaderLibrary.Program program = null;
            Map<String, String> bindings = new LinkedHashMap<>();
            if (operation.equals("COMPUTE")) {
                program = library.program(value.get("runtimeProgramId").getAsString());
                JsonObject refs = value.getAsJsonObject("bindingRefs");
                for (var ref : refs.entrySet()) {
                    String target = ref.getValue().getAsString();
                    if (!names.contains(target)) throw new IOException("Missing frame binding producer: " + name + "." + ref.getKey());
                    bindings.put(ref.getKey(), target);
                }
                Set<String> fields = program.bindingsLayout().fields().stream()
                        .map(CloudlyShaderLibrary.BindingField::name).collect(java.util.stream.Collectors.toSet());
                if (!fields.equals(bindings.keySet())) throw new IOException("Incomplete source frame binding ABI: " + name);
                for (var field : program.bindingsLayout().fields()) {
                    Resource resource = resourceIndex.get(bindings.get(field.name()));
                    boolean sampler = resource.kind() == Kind.SAMPLER || resource.kind() == Kind.EXTERNAL
                            && resource.spec().get("descriptorType").getAsString().equals("SAMPLER");
                    if (field.heap().equals("Sampler") != sampler) {
                        throw new IOException("Frame producer has the wrong descriptor heap: " + name + "." + field.name());
                    }
                    if (resource.kind() == Kind.IMAGE && field.type().contains("Texture")) {
                        int dimensions = resource.spec().get("dimension").getAsString().equals("TWO_D") ? 2 : 3;
                        if (!field.type().contains("Texture" + dimensions + "D")) {
                            throw new IOException("Frame producer has the wrong image dimension: " + name + "." + field.name());
                        }
                    }
                    if (resource.kind() == Kind.BUFFER && field.type().startsWith("RWBuffer<")) {
                        if (!resource.spec().has("descriptorType") || !resource.spec().get("descriptorType").getAsString().equals("STORAGE_TEXEL_BUFFER")) {
                            throw new IOException("Typed source buffer needs its texel descriptor: " + name + "." + field.name());
                        }
                    }
                }
                if (value.has("commonOverrides")) {
                    for (String field : value.getAsJsonObject("commonOverrides").keySet()) {
                        program.parametersLayout().field(field);
                    }
                }
            }
            for (String section : List.of("resources", "inputs", "outputs")) {
                if (!value.has(section)) continue;
                for (String reference : strings(value.getAsJsonArray(section))) {
                    // A terminal host publication declares an external output, rather than a source GPU resource.
                    if (section.equals("outputs") && operation.equals("HOST_AFTER_RR_COMPOSITION")) continue;
                    if (!names.contains(reference)) throw new IOException("Missing source frame resource: " + name + "." + reference);
                }
            }
            Stage stage = new Stage(name, operation, dependencies, program, Map.copyOf(bindings), value.deepCopy());
            stages.add(stage);
            preceding.add(name);
        }
        return new CloudlyFrameSchedule(root.get("executionReady").getAsBoolean(), resources, stages);
    }

    public boolean executionReady() { return executionReady; }

    public void requireExecutionReady() {
        if (!executionReady) throw new IllegalStateException("Original frame schedule still has unresolved execution producers");
    }

    public List<Resource> resources() { return resources; }
    public List<Stage> stages() { return stages; }
    public Stage stage(String name) { return java.util.Objects.requireNonNull(byName.get(name), "Unknown source frame stage " + name); }

    /** Extents are real caller inputs. Missing dimensions never become a one-pixel substitute. */
    public Resolved resolve(Map<String, double[]> inputs) {
        Map<String, Extent> images = new LinkedHashMap<>();
        Map<String, BufferExtent> buffers = new LinkedHashMap<>();
        for (Resource resource : resources) {
            JsonObject value = resource.spec();
            if (resource.kind() == Kind.IMAGE) {
                JsonArray dimensions = value.getAsJsonArray("extent");
                int expected = value.get("dimension").getAsString().equals("TWO_D") ? 2 : 3;
                if (dimensions.size() != expected) throw new IllegalArgumentException("Wrong source image dimension " + resource.name());
                int width = positiveInteger(dimensions.get(0), inputs);
                int height = positiveInteger(dimensions.get(1), inputs);
                int depth = expected == 3 ? positiveInteger(dimensions.get(2), inputs) : 1;
                images.put(resource.name(), new Extent(width, height, depth, value.get("format").getAsInt(),
                        value.get("mipLevels").getAsInt()));
            } else if (resource.kind() == Kind.BUFFER) {
                int stride = positiveInteger(value.get("strideBytes"), inputs);
                long count = positiveLong(value.get("elements"), inputs);
                buffers.put(resource.name(), new BufferExtent(stride, count, Math.multiplyExact(stride, count),
                        value.has("descriptorType") ? value.get("descriptorType").getAsString() : "STORAGE_BUFFER",
                        value.has("format") ? value.get("format").getAsInt() : 0));
            }
        }
        Map<String, Dispatch> dispatches = new LinkedHashMap<>();
        for (Stage stage : stages) {
            if (stage.program() == null) continue;
            JsonObject value = stage.spec().getAsJsonObject("dispatch");
            if (value.has("indirectBuffer")) {
                String buffer = value.get("indirectBuffer").getAsString();
                BufferExtent extent = java.util.Objects.requireNonNull(buffers.get(buffer), "Missing indirect source buffer " + buffer);
                long offset = value.get("byteOffset").getAsLong();
                if (offset < 0 || (offset & 3) != 0 || offset > extent.byteSize() - 12) {
                    throw new IllegalArgumentException("Source indirect dispatch leaves its argument buffer: " + stage.name());
                }
                dispatches.put(stage.name(), new IndirectDispatch(buffer, offset));
            } else if (value.has("groupCounts")) {
                JsonArray groups = value.getAsJsonArray("groupCounts");
                if (groups.size() != 3) throw new IllegalArgumentException("Source dispatch requires three group counts");
                dispatches.put(stage.name(), new DirectDispatch(positiveInteger(groups.get(0), inputs),
                        positiveInteger(groups.get(1), inputs), positiveInteger(groups.get(2), inputs)));
            } else {
                double[] raw = required(inputs, value.get("extentInput").getAsString());
                int[] work = {1,1,1};
                if (raw.length < 2 || raw.length > 3) throw new IllegalArgumentException("Source work extent requires two or three components");
                for (int axis = 0; axis < raw.length; axis++) work[axis] = positiveInteger(raw[axis]);
                int[] threads = ints(value.getAsJsonArray("localSize"));
                int[] multiplier = ints(value.getAsJsonArray("multiplier"));
                if (value.has("integerDivideBeforeCeilInput")) {
                    double[] split = required(inputs, value.get("integerDivideBeforeCeilInput").getAsString());
                    if (split.length != 2) throw new IllegalArgumentException("Source split grid requires two dimensions");
                    work[0] /= positiveInteger(split[0]); work[1] /= positiveInteger(split[1]);
                }
                int[] counts = new int[3];
                for (int axis = 0; axis < 3; axis++) {
                    if (work[axis] < 1 || threads[axis] < 1 || multiplier[axis] < 1) {
                        throw new IllegalArgumentException("Invalid source dispatch extent: " + stage.name());
                    }
                    counts[axis] = Math.multiplyExact(Math.ceilDiv(work[axis], threads[axis]), multiplier[axis]);
                }
                if (value.has("zGroups")) counts[2] = positiveInteger(value.get("zGroups"), inputs);
                dispatches.put(stage.name(), new DirectDispatch(counts[0], counts[1], counts[2]));
            }
        }
        return new Resolved(Map.copyOf(images), Map.copyOf(buffers), Map.copyOf(dispatches));
    }

    /** Resolve each table at its stage, after any native CPU ring rotation has updated descriptor roles. */
    public BoundTable bindings(String name, BindingResolver resolver) {
        Stage stage = stage(name);
        if (stage.program() == null) throw new IllegalArgumentException("A host operation has no shader binding table");
        Map<String, GpuDescriptorIndex> table = new LinkedHashMap<>();
        for (var field : stage.program().bindingsLayout().fields()) {
            GpuDescriptorIndex descriptor = java.util.Objects.requireNonNull(
                    resolver.resolve(stage.bindingRefs().get(field.name()), field), "Missing source descriptor " + field.name());
            table.put(field.name(), descriptor);
        }
        ByteBuffer bytes = ByteBuffer.allocate(stage.program().bindingByteSize()).order(ByteOrder.LITTLE_ENDIAN);
        stage.program().bindingsLayout().write(bytes, table);
        return new BoundTable(Map.copyOf(table), bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
    }

    /** Source subpass overrides patch an immutable recipe result into a new CPU payload. */
    public ByteBuffer constants(String name, ByteBuffer parameters) {
        Stage stage = stage(name);
        var program = java.util.Objects.requireNonNull(stage.program(), "Host operation has no constants");
        if (parameters.remaining() != program.parametersByteSize()) throw new IllegalArgumentException("Wrong original frame constants size");
        ByteBuffer output = ByteBuffer.allocate(parameters.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        output.put(parameters.duplicate()).flip();
        JsonObject value = stage.spec();
        if (value.has("commonOverrides")) for (var entry : value.getAsJsonObject("commonOverrides").entrySet()) {
            var field = program.parametersLayout().field(entry.getKey());
            double[] numbers = CloudlyUniformExpressions.evaluate(entry.getValue(), Map.of());
            if (field.type().startsWith("float")) {
                float[] values = new float[numbers.length];
                for (int index = 0; index < values.length; index++) values[index] = (float)numbers[index];
                program.parametersLayout().putFloats(output, entry.getKey(), values);
            } else {
                int[] values = new int[numbers.length];
                for (int index = 0; index < values.length; index++) {
                    double number = numbers[index];
                    boolean unsigned = field.type().startsWith("uint");
                    if (!Double.isFinite(number) || number != Math.rint(number)
                            || number < (unsigned ? 0 : Integer.MIN_VALUE)
                            || number > (unsigned ? 0xffff_ffffL : Integer.MAX_VALUE)) {
                        throw new IllegalArgumentException("Original integer override is out of range");
                    }
                    values[index] = (int)(long)number;
                }
                program.parametersLayout().putInts(output, entry.getKey(), values);
            }
        }
        return output.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static List<String> strings(JsonArray array) {
        return array.asList().stream().map(JsonElement::getAsString).toList();
    }

    private static int[] ints(JsonArray array) {
        if (array.size() != 3) throw new IllegalArgumentException("Source dispatch tuple requires three values");
        return array.asList().stream().mapToInt(JsonElement::getAsInt).toArray();
    }

    private static double[] required(Map<String, double[]> inputs, String name) {
        return java.util.Objects.requireNonNull(inputs.get(name), "Missing source frame extent " + name);
    }

    private static double number(JsonElement expression, Map<String, double[]> inputs) {
        if (!expression.isJsonPrimitive() || !expression.getAsJsonPrimitive().isString()) return expression.getAsDouble();
        String name = expression.getAsString();
        double[] direct = inputs.get(name);
        if (direct != null) {
            if (direct.length != 1) throw new IllegalArgumentException("Source extent scalar has the wrong width " + name);
            return direct[0];
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            int axis = switch (name.substring(dot + 1)) { case "x" -> 0; case "y" -> 1; case "z" -> 2; default -> -1; };
            if (axis >= 0) {
                double[] vector = required(inputs, name.substring(0, dot));
                if (axis >= vector.length) throw new IllegalArgumentException("Source extent vector has the wrong width " + name);
                return vector[axis];
            }
        }
        throw new IllegalArgumentException("Missing source frame scalar " + name);
    }

    private static long positiveLong(JsonElement value, Map<String, double[]> inputs) {
        double number = number(value, inputs);
        if (!Double.isFinite(number) || number < 1 || number != Math.rint(number) || number > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("Source extent is not a positive exact integer");
        }
        return (long)number;
    }

    private static int positiveInteger(JsonElement value, Map<String, double[]> inputs) {
        return Math.toIntExact(positiveLong(value, inputs));
    }

    private static int positiveInteger(double value) {
        if (!Double.isFinite(value) || value < 1 || value != Math.rint(value) || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Source extent is not a positive integer");
        }
        return (int)value;
    }

    public enum Kind { IMAGE, BUFFER, SAMPLER, EXTERNAL }
    public record Resource(String name, Kind kind, JsonObject spec) {
        public Resource { spec = spec.deepCopy(); }
        @Override public JsonObject spec() { return spec.deepCopy(); }
    }
    public record Stage(String name, String operation, List<String> dependsOn, CloudlyShaderLibrary.Program program,
                        Map<String, String> bindingRefs, JsonObject spec) {
        public Stage { dependsOn = List.copyOf(dependsOn); bindingRefs = Map.copyOf(bindingRefs); spec = spec.deepCopy(); }
        @Override public JsonObject spec() { return spec.deepCopy(); }
    }
    public record Extent(int width, int height, int depth, int format, int mipLevels) { }
    public record BufferExtent(int strideBytes, long elements, long byteSize, String descriptorType, int format) { }
    public sealed interface Dispatch permits DirectDispatch, IndirectDispatch { }
    public record DirectDispatch(int x, int y, int z) implements Dispatch { }
    public record IndirectDispatch(String argumentBuffer, long byteOffset) implements Dispatch { }
    public record Resolved(Map<String, Extent> images, Map<String, BufferExtent> buffers, Map<String, Dispatch> dispatches) { }
    public record BoundTable(Map<String, GpuDescriptorIndex> descriptors, ByteBuffer bytes) { }
    @FunctionalInterface public interface BindingResolver {
        GpuDescriptorIndex resolve(String resourceName, CloudlyShaderLibrary.BindingField originalField);
    }
}
