package dev.comfyfluffy.caustica.rt.pass;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.ComputeBinding;
import dev.comfyfluffy.caustica.api.pass.ComputeImageKind;
import dev.comfyfluffy.caustica.api.pass.ComputeProgram;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts and compiles one extension-owned compute program, then validates its named resource ABI. */
final class PassShaderCompiler {
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");

    private PassShaderCompiler() {
    }

    static CompiledProgram compile(Path cacheRoot, ComputeProgram program) throws IOException {
        Path directory = cacheRoot.resolve(program.id().getNamespace()).resolve(program.id().getPath());
        Files.createDirectories(directory);
        Map<String, String> modules = new LinkedHashMap<>();
        extract(program.shaderSource(), program.module(), directory, modules, new LinkedHashSet<>());
        String source = modules.get(program.module());
        Path sourcePath = directory.resolve(program.module() + ".slang");
        long startNanos = System.nanoTime();
        SlangCompileResult result;
        try (SlangSession session = SlangRuntime.INSTANCE.openSession(List.of(directory), true, true)) {
            result = session.compile(program.module(), sourcePath.toString(), source, program.entryPoint());
        }
        validateBindings(program, result.reflectionJson());
        CausticaMod.LOGGER.info("Compiled render-pass program {} in {} ms ({} bytes SPIR-V)",
                program.id(), String.format(java.util.Locale.ROOT, "%.1f",
                        (System.nanoTime() - startNanos) / 1.0e6), result.spirv().length);
        return new CompiledProgram(result.spirv());
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

    private static void validateBindings(ComputeProgram program, String reflectionJson) throws IOException {
        JsonObject reflection = JsonParser.parseString(reflectionJson).getAsJsonObject();
        JsonArray parameters = reflection.getAsJsonArray("parameters");
        Map<String, BindingLocation> reflected = new LinkedHashMap<>();
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
            JsonObject type = parameter.getAsJsonObject("type");
            ComputeImageKind imageKind = type.has("combined") && type.get("combined").getAsBoolean()
                    ? ComputeImageKind.SAMPLED_LINEAR : ComputeImageKind.STORAGE;
            reflected.put(parameter.get("name").getAsString(),
                    new BindingLocation(binding.get("index").getAsInt(), space, imageKind));
        }
        List<String> problems = new ArrayList<>();
        List<ComputeBinding> declared = program.bindings();
        for (int index = 0; index < declared.size(); index++) {
            ComputeBinding binding = declared.get(index);
            BindingLocation location = reflected.remove(binding.name());
            if (location == null) {
                problems.add("missing " + binding.name());
            } else if (location.index() != index || location.space() != 0) {
                problems.add(binding.name() + " reflected at set " + location.space()
                        + " binding " + location.index() + ", expected set 0 binding " + index);
            } else if (location.kind() != binding.kind()) {
                problems.add(binding.name() + " reflected as " + location.kind()
                        + ", declared as " + binding.kind());
            }
        }
        if (!reflected.isEmpty()) {
            problems.add("undeclared " + reflected.keySet());
        }
        if (reflectedPushConstantBytes != program.pushConstantBytes()) {
            problems.add("push constants reflected as " + reflectedPushConstantBytes
                    + " bytes, declared as " + program.pushConstantBytes());
        }
        JsonObject entryPoint = null;
        for (var element : reflection.getAsJsonArray("entryPoints")) {
            JsonObject candidate = element.getAsJsonObject();
            if (program.entryPoint().equals(candidate.get("name").getAsString())) {
                entryPoint = candidate;
                break;
            }
        }
        if (entryPoint == null || !"compute".equals(entryPoint.get("stage").getAsString())) {
            problems.add("missing compute entry point " + program.entryPoint());
        } else {
            JsonArray group = entryPoint.getAsJsonArray("threadGroupSize");
            if (group.get(0).getAsInt() != program.localSizeX()
                    || group.get(1).getAsInt() != program.localSizeY()
                    || group.get(2).getAsInt() != program.localSizeZ()) {
                problems.add("thread group size does not match declaration");
            }
        }
        if (!problems.isEmpty()) {
            throw new IOException(program.id() + " resource layout mismatch: " + String.join("; ", problems));
        }
    }

    record CompiledProgram(byte[] spirv) {
    }

    private record BindingLocation(int index, int space, ComputeImageKind kind) {
    }
}
