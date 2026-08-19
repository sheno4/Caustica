package dev.comfyfluffy.caustica.slang;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.ComputeDispatch;
import dev.comfyfluffy.caustica.api.pass.PassShaderCompiler;

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

/** Slang-backed pass shader compiler installed by the host before render passes are created. */
public final class SlangPassShaderCompiler implements PassShaderCompiler {
    private static final System.Logger LOGGER = System.getLogger(SlangPassShaderCompiler.class.getName());
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
    private static volatile SlangPassShaderCompiler installed = new SlangPassShaderCompiler(Path.of(
            System.getProperty("java.io.tmpdir"), "caustica-shaders", "passes"));

    private final Path cacheRoot;
    private final Map<ProgramCacheKey, CompiledProgram> programCache = new ConcurrentHashMap<>();

    public SlangPassShaderCompiler(Path cacheRoot) {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
    }

    /** Installs the process-wide compiler returned to render passes by their setup context. */
    public static void install(Path cacheRoot) {
        installed = new SlangPassShaderCompiler(cacheRoot);
    }

    public static SlangPassShaderCompiler installed() {
        return installed;
    }

    @Override
    public CompiledProgram compile(ResourceId id, ShaderSource source, String module, String entryPoint)
            throws IOException {
        Path directory = cacheRoot.resolve(id.namespace()).resolve(id.path());
        Files.createDirectories(directory);
        Map<String, String> modules = new LinkedHashMap<>();
        extract(source, module, directory, modules, new LinkedHashSet<>());
        ProgramCacheKey cacheKey = new ProgramCacheKey(id, Map.copyOf(modules), module, entryPoint);
        CompiledProgram cached = programCache.get(cacheKey);
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
        CompiledProgram existing = programCache.putIfAbsent(cacheKey, compiled);
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

    @Override
    public void validateBindings(ResourceId id, String reflectionJson,
                                 List<ComputeDispatch.Binding> expected,
                                 int pushConstantBytes, String entryPoint,
                                 int localSizeX, int localSizeY, int localSizeZ) throws IOException {
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

    private record ProgramCacheKey(ResourceId id, Map<String, String> modules,
                                   String module, String entryPoint) {
    }

    private record ReflectedBinding(String name, ComputeDispatch.Binding kind) {
    }
}
