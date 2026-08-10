package dev.comfyfluffy.caustica.api.pass;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.slang.SlangCompileResult;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and compiles one pass-owned compute shader from a {@link ShaderSource}. A pass calls this
 * itself (it is not driven by any declarative program description) and optionally calls
 * {@link #validateBindings} against the pipeline layout it built, to catch descriptor-layout drift
 * between the .slang source and the hand-written Vulkan side.
 *
 * <p>Public pass-authoring helper, usable by any {@code CausticaRenderPass} (engine-bundled or
 * extension-owned) — lives alongside {@link CausticaRenderPass}/{@link PassSetup}/{@link PassFrame} in the
 * public API package rather than an engine-internal one, since third-party passes are exactly who this is
 * for.
 */
public final class PassShaderCompiler {
    private static final System.Logger LOGGER = System.getLogger(PassShaderCompiler.class.getName());
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
    private static final Map<ProgramCacheKey, CompiledProgram> PROGRAM_CACHE = new ConcurrentHashMap<>();
    private static volatile Path defaultCacheRoot = Path.of(
            System.getProperty("java.io.tmpdir"), "caustica-shaders", "passes");

    private PassShaderCompiler() {
    }

    /** Shared source-extraction cache root every built-in pass compiles under. */
    public static Path defaultCacheRoot() {
        return defaultCacheRoot;
    }

    /** Configures the process-wide cache location before passes are created. */
    public static void defaultCacheRoot(Path cacheRoot) {
        defaultCacheRoot = cacheRoot.toAbsolutePath().normalize();
    }

    public static CompiledProgram compile(Path cacheRoot, ResourceId id, ShaderSource source, String module,
                                   String entryPoint) throws IOException {
        Path directory = cacheRoot.resolve(id.namespace()).resolve(id.path());
        Files.createDirectories(directory);
        Map<String, String> modules = new LinkedHashMap<>();
        extract(source, module, directory, modules, new LinkedHashSet<>());
        ProgramCacheKey cacheKey = new ProgramCacheKey(id, Map.copyOf(modules), module, entryPoint);
        CompiledProgram cached = PROGRAM_CACHE.get(cacheKey);
        if (cached != null) {
            LOGGER.log(System.Logger.Level.DEBUG, "Reusing cached render-pass program {0}", id);
            return cached;
        }
        String moduleSource = modules.get(module);
        Path sourcePath = directory.resolve(module + ".slang");
        long startNanos = System.nanoTime();
        SlangCompileResult result;
        try (SlangSession session = SlangRuntime.INSTANCE.openSession(List.of(directory), true, true)) {
            result = session.compile(module, sourcePath.toString(), moduleSource, entryPoint);
        }
        LOGGER.log(System.Logger.Level.INFO, "Compiled render-pass program {0} in {1} ms ({2} bytes SPIR-V)",
                id, String.format(java.util.Locale.ROOT, "%.1f",
                        (System.nanoTime() - startNanos) / 1.0e6), result.spirv().length);
        CompiledProgram compiled = new CompiledProgram(result.spirv(), result.reflectionJson());
        CompiledProgram existing = PROGRAM_CACHE.putIfAbsent(cacheKey, compiled);
        return existing != null ? existing : compiled;
    }

    private static void extract(ShaderSource source, String module, Path directory,
                                Map<String, String> modules, Set<String> visiting) throws IOException {
        if (modules.containsKey(module)) {
            return;
        }
        if (!visiting.add(module)) {
            throw new IOException("cyclic render-pass shader import involving " + module);
        }
        String text;
        try (InputStream input = source.openModule(module)) {
            if (input == null) {
                throw new IOException("missing render-pass shader module " + module);
            }
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher imports = IMPORT.matcher(text);
        while (imports.find()) {
            extract(source, imports.group(1), directory, modules, visiting);
        }
        Files.writeString(directory.resolve(module + ".slang"), text, StandardCharsets.UTF_8);
        modules.put(module, text);
        visiting.remove(module);
    }

    /**
     * Position-based check: binding {@code i} at set 0 must reflect as {@code expected.get(i)}'s kind, no
     * bindings may be undeclared or missing, push-constant size must match, and the entry point must
     * exist as a compute stage with the given thread group size.
     */
    public static void validateBindings(ResourceId id, String reflectionJson, List<ComputeDispatch.Binding> expected,
                                 int pushConstantBytes, String entryPoint, int localSizeX, int localSizeY,
                                 int localSizeZ) throws IOException {
        JsonObject reflection = JsonParser.parseString(reflectionJson).getAsJsonObject();
        JsonArray parameters = reflection.getAsJsonArray("parameters");
        Map<Integer, ReflectedBinding> reflected = new LinkedHashMap<>();
        int reflectedPushConstantBytes = 0;
        for (var element : parameters) {
            JsonObject parameter = element.getAsJsonObject();
            JsonObject binding = parameter.has("binding") ? parameter.getAsJsonObject("binding") : null;
            if (binding == null) {
                continue;
            }
            String bindingKind = binding.get("kind").getAsString();
            if ("pushConstantBuffer".equals(bindingKind)) {
                reflectedPushConstantBytes = parameter.getAsJsonObject("type")
                        .getAsJsonObject("elementVarLayout").getAsJsonObject("binding")
                        .get("size").getAsInt();
                continue;
            }
            if (!"descriptorTableSlot".equals(bindingKind)) {
                continue;
            }
            int space = binding.has("space") ? binding.get("space").getAsInt() : 0;
            if (space != 0) {
                continue;
            }
            JsonObject type = parameter.getAsJsonObject("type");
            ComputeDispatch.Binding kind = type.has("combined") && type.get("combined").getAsBoolean()
                    ? ComputeDispatch.Binding.SAMPLED : ComputeDispatch.Binding.STORAGE;
            reflected.put(binding.get("index").getAsInt(),
                    new ReflectedBinding(parameter.get("name").getAsString(), kind));
        }
        List<String> problems = new ArrayList<>();
        for (int index = 0; index < expected.size(); index++) {
            ReflectedBinding location = reflected.remove(index);
            if (location == null) {
                problems.add("missing binding " + index);
            } else if (location.kind() != expected.get(index)) {
                problems.add("binding " + index + " (" + location.name() + ") reflected as "
                        + location.kind() + ", expected " + expected.get(index));
            }
        }
        if (!reflected.isEmpty()) {
            problems.add("undeclared bindings " + reflected);
        }
        if (reflectedPushConstantBytes != pushConstantBytes) {
            problems.add("push constants reflected as " + reflectedPushConstantBytes
                    + " bytes, expected " + pushConstantBytes);
        }
        JsonObject foundEntryPoint = null;
        for (var element : reflection.getAsJsonArray("entryPoints")) {
            JsonObject candidate = element.getAsJsonObject();
            if (entryPoint.equals(candidate.get("name").getAsString())) {
                foundEntryPoint = candidate;
                break;
            }
        }
        if (foundEntryPoint == null || !"compute".equals(foundEntryPoint.get("stage").getAsString())) {
            problems.add("missing compute entry point " + entryPoint);
        } else {
            JsonArray group = foundEntryPoint.getAsJsonArray("threadGroupSize");
            if (group.get(0).getAsInt() != localSizeX || group.get(1).getAsInt() != localSizeY
                    || group.get(2).getAsInt() != localSizeZ) {
                problems.add("thread group size does not match declaration");
            }
        }
        if (!problems.isEmpty()) {
            throw new IOException(id + " resource layout mismatch: " + String.join("; ", problems));
        }
    }

    public record CompiledProgram(byte[] spirv, String reflectionJson) {
    }

    private record ProgramCacheKey(ResourceId id, Map<String, String> modules,
                                   String module, String entryPoint) {
    }

    private record ReflectedBinding(String name, ComputeDispatch.Binding kind) {
    }
}
