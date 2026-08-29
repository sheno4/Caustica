package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramKey;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles one immutable engine {@link ProgramComposition} into specialized world shader stages. */
public final class WorldShaderCompiler implements ProgramBackend.CompiledProgram, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldShaderCompiler.class);
    public static final String SKY_MISS_MODULE = "sky_miss";
    public static final String CLOSEST_HIT_MODULE = "closest_hit";
    public static final String PRIMARY_MODULE = "primary_rgen";
    public static final String INDIRECT_MODULE = "indirect";
    public static final String INDIRECT_SER_MODULE = "indirect_ser";
    public static final String RADIANCE_ANY_HIT_MODULE = "radiance_any_hit_rahit";
    public static final String SHADOW_ANY_HIT_MODULE = "shadow_any_hit_rahit";
    public static final String ENTRY_POINT = "main";

    private static final String WORLD_SHADER_ROOT = "/caustica/shaders/world/";
    private static final String API_ROOT = "/caustica/shaders/api/";
    private static final String BUILTIN_ROOT = "/caustica/shaders/builtin/";
    private static final String COMPOSITION_MODULE = "caustica_composition";
    private static final String COMPOSITION_TYPE = "Composition";
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;");

    private static final List<String> WORLD_MODULES = List.of(
            "bindings.slang", "world_common.slang", "world_minimal.slang", "primary_rgen.slang",
            "indirect.slang", "indirect_ser.slang", "closest_hit.slang", "sky_miss.slang",
            "radiance_any_hit.rahit.slang", "shadow_any_hit.rahit.slang", "guide.rmiss.slang",
            "retained_lights.slang", "surface_bsdf.slang", "path_queue_types.slang",
            "retained_path_queue.slang", "retained_indirect.slang", "retained_trace_policy.slang",
            "retained_trace_ordinary.slang", "retained_trace_reordered.slang");
    private static final List<String> API_MODULES = List.of(
            "caustica_api.slang", "caustica_color.slang", "caustica_coverage.slang",
            "caustica_environment.slang", "caustica_resources.slang", "caustica_surface.slang",
            "caustica_types.slang", "caustica_volume.slang");
    private static final List<String> BUILTIN_MODULES = List.of(
            "surface/caustica_error_surface.slang", "surface/caustica_error_coverage.slang",
            "sky/caustica_builtin_sky.slang", "sky/caustica_error_environment.slang");
    private static final Set<String> PROVIDED_MODULES = moduleNames(WORLD_MODULES, API_MODULES, BUILTIN_MODULES);

    static Set<String> missingBundledWorldImports() throws IOException {
        Set<String> missing = new java.util.LinkedHashSet<>();
        for (String file : WORLD_MODULES) {
            try (InputStream input = WorldShaderCompiler.class.getResourceAsStream(WORLD_SHADER_ROOT + file)) {
                if (input == null) throw new IOException("missing bundled world shader " + file);
                String source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                var imports = IMPORT.matcher(source);
                while (imports.find()) {
                    String module = imports.group(1);
                    if (!PROVIDED_MODULES.contains(module)) missing.add(module);
                }
            }
        }
        return Set.copyOf(missing);
    }

    private final SlangSession session;
    private final Path worldDirectory;
    private final Path cleanupDirectory;
    private final Composition composition;
    private final Map<String, byte[]> spirvByKey = new ConcurrentHashMap<>();

    private WorldShaderCompiler(SlangSession session, Path worldDirectory, Path cleanupDirectory,
                                Composition composition) {
        this.session = session;
        this.worldDirectory = worldDirectory;
        this.cleanupDirectory = cleanupDirectory;
        this.composition = composition;
    }

    public static WorldShaderCompiler create(SlangRuntime runtime, Path cacheDirectory,
                                             ProgramComposition program)
            throws IOException {
        return create(runtime, cacheDirectory, program, null);
    }

    public static WorldShaderCompiler createIsolated(SlangRuntime runtime, Path cacheRoot,
                                                     ProgramComposition program)
            throws IOException {
        Objects.requireNonNull(runtime, "runtime");
        Files.createDirectories(cacheRoot);
        Path directory = Files.createTempDirectory(cacheRoot, "runtime-");
        try {
            return create(runtime, directory, program, directory);
        } catch (IOException | RuntimeException | Error failure) {
            deleteDirectory(directory);
            throw failure;
        }
    }

    private static WorldShaderCompiler create(SlangRuntime runtime, Path cacheDirectory,
                                              ProgramComposition program,
                                              Path cleanupDirectory) throws IOException {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        Objects.requireNonNull(program, "program");
        Path worldDirectory = cacheDirectory.resolve("world");
        Path apiDirectory = cacheDirectory.resolve("api");
        Path builtinDirectory = cacheDirectory.resolve("builtin");
        Path extensionDirectory = cacheDirectory.resolve("extensions");
        Path compositionDirectory = cacheDirectory.resolve("composition");

        Map<String, byte[]> sources = new LinkedHashMap<>();
        extractClasspath(WORLD_SHADER_ROOT, WORLD_MODULES, worldDirectory, "world", sources);
        writeSpecializedModuleAlias(worldDirectory, "radiance_any_hit.rahit.slang", RADIANCE_ANY_HIT_MODULE);
        writeSpecializedModuleAlias(worldDirectory, "shadow_any_hit.rahit.slang", SHADOW_ANY_HIT_MODULE);
        extractClasspath(API_ROOT, API_MODULES, apiDirectory, "api", sources);
        extractClasspath(BUILTIN_ROOT, BUILTIN_MODULES, builtinDirectory, "builtin", sources);

        List<ProgramComposition.Declaration> declarations = program.declarations();
        Map<String, ResolvedModule> modules = resolveModules(declarations);
        Files.createDirectories(extensionDirectory);
        for (ResolvedModule module : modules.values()) {
            Path destination = extensionDirectory.resolve(module.name().replace('.', '/') + ".slang");
            Files.createDirectories(destination.getParent());
            Files.write(destination, module.bytes());
            sources.put("extension/" + module.name() + ".slang", module.bytes());
        }

        GeneratedComposition generated = compositionRoot(program);
        Files.createDirectories(compositionDirectory);
        Files.writeString(compositionDirectory.resolve(COMPOSITION_MODULE + ".slang"), generated.source(),
                StandardCharsets.UTF_8);
        List<Path> searchPaths = List.of(worldDirectory, apiDirectory, builtinDirectory,
                extensionDirectory, compositionDirectory);
        SlangSession session = runtime.openSession(searchPaths, false, true);
        Composition composition = Composition.create(generated.indices(), generated.data(),
                COMPOSITION_MODULE, COMPOSITION_TYPE, generated.source(), sources);
        return new WorldShaderCompiler(session, worldDirectory, cleanupDirectory, composition);
    }

    Composition composition() {
        return composition;
    }

    /** Returns the packed implementation constants consumed by the compiled composition. */
    public List<Long> implementationData() {
        return composition.implementationData();
    }

    @Override
    public int implementationIndex(ProgramKey key) {
        return composition.implementationIndex(key);
    }

    public byte[] compileSpecialized(String engineModule, String entryPoint) {
        String key = "composition:" + composition.contentHash() + '/' + engineModule + '/' + entryPoint;
        return cached(key, () -> session.compileSpecialized(engineModule, entryPoint,
                composition.rootModule(), composition.rootType()).spirv(),
                engineModule + ':' + entryPoint + " for " + composition.contentHash().substring(0, 12));
    }

    public byte[] compileSkyMiss() { return compileSpecialized(SKY_MISS_MODULE, ENTRY_POINT); }
    public byte[] compileClosestHit() { return compileSpecialized(CLOSEST_HIT_MODULE, ENTRY_POINT); }
    public byte[] compileRadianceAnyHit() { return compileSpecialized(RADIANCE_ANY_HIT_MODULE, ENTRY_POINT); }
    public byte[] compileShadowAnyHit() { return compileSpecialized(SHADOW_ANY_HIT_MODULE, ENTRY_POINT); }
    public byte[] compilePrimary() { return compileSpecialized(PRIMARY_MODULE, ENTRY_POINT); }
    public byte[] compileIndirect(boolean reordered) {
        return compileSpecialized(reordered ? INDIRECT_SER_MODULE : INDIRECT_MODULE, ENTRY_POINT);
    }

    public byte[] compilePlain(String moduleFileName, String entryPoint) {
        Objects.requireNonNull(moduleFileName, "moduleFileName");
        Objects.requireNonNull(entryPoint, "entryPoint");
        return cached("plain:" + moduleFileName + '/' + entryPoint, () -> {
            Path file = worldDirectory.resolve(moduleFileName);
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String base = moduleFileName.substring(0, moduleFileName.length() - ".slang".length());
                return session.compile(base.replace('.', '_'), file.toString(), source, entryPoint).spirv();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + file, e);
            }
        }, moduleFileName + ':' + entryPoint);
    }

    private byte[] cached(String key, Supplier<byte[]> compile, String label) {
        return spirvByKey.computeIfAbsent(key, ignored -> {
            long startNanos = System.nanoTime();
            byte[] spirv = compile.get();
            LOGGER.info(String.format(Locale.ROOT,
                    "Compiled world shader %s in %.1f ms (%d bytes SPIR-V)", label,
                    (System.nanoTime() - startNanos) / 1.0e6, spirv.length));
            return spirv;
        });
    }

    private static GeneratedComposition compositionRoot(ProgramComposition program) {
        List<ProgramComposition.Surface> surfaces = new ArrayList<>();
        List<ProgramComposition.Volume> volumes = new ArrayList<>();
        List<ProgramComposition.Environment> environments = new ArrayList<>();
        program.declarations().forEach(declaration -> {
            switch (declaration) {
                case ProgramComposition.Surface surface -> surfaces.add(surface);
                case ProgramComposition.Volume volume -> volumes.add(volume);
                case ProgramComposition.Environment environment -> environments.add(environment);
            }
        });
        Map<ProgramKey, Integer> indices = new LinkedHashMap<>();
        Map<ProgramKey, Integer> dataOffsets = new LinkedHashMap<>();
        List<Long> data = new ArrayList<>();
        for (int index = 0; index < surfaces.size(); index++) {
            ProgramComposition.Surface surface = surfaces.get(index);
            indices.put(surface.key(), index + 1);
            dataOffsets.put(surface.key(), data.size());
            data.add(surface.definition().implementationData().bits());
        }
        for (int index = 0; index < volumes.size(); index++) {
            ProgramComposition.Volume volume = volumes.get(index);
            indices.put(volume.key(), index + 1);
            dataOffsets.put(volume.key(), data.size());
            data.add(volume.definition().implementationData().bits());
        }
        for (int index = 0; index < environments.size(); index++) {
            indices.put(environments.get(index).key(), index + 1);
        }

        StringBuilder source = new StringBuilder("module ").append(COMPOSITION_MODULE).append(";\n\n")
                .append("import caustica_api;\nimport caustica_types;\nimport caustica_surface;\n")
                .append("import caustica_coverage;\nimport caustica_volume;\nimport caustica_environment;\n")
                .append("import caustica_error_surface;\nimport caustica_error_coverage;\n")
                .append("import caustica_builtin_sky;\nimport caustica_error_environment;\n");
        program.declarations().stream().flatMap(WorldShaderCompiler::definitions).map(ShaderDefinition::module)
                .distinct().sorted().forEach(module -> source.append("import ").append(module).append(";\n"));

        source.append("\npublic struct SurfaceDispatch : ISurfaceDispatch {\n")
                .append("    public OpenPbrSurface evaluateSurface(uint implementation, SurfaceInput input) {\n")
                .append("        switch (implementation) {\n");
        for (int i = 0; i < surfaces.size(); i++) {
            ProgramComposition.Surface value = surfaces.get(i);
            source.append("            case ").append(i + 1).append("u: { input.implementationData = ")
                    .append("ShaderDataPtr<uint64_t>(input.shaderRoot.compositionData)[")
                    .append(dataOffsets.get(value.key())).append("u]; ")
                    .append(value.definition().surface().type())
                    .append(" value; return value.evaluateSurface(input); }\n");
        }
        source.append("            default: { ErrorSurface value; return value.evaluateSurface(input); }\n")
                .append("        }\n    }\n};\n\n")
                .append("public struct CoverageDispatch : ICoverageDispatch {\n")
                .append("    public float evaluateCoverage(uint implementation, CoverageInput input) {\n")
                .append("        switch (implementation) {\n");
        for (int i = 0; i < surfaces.size(); i++) {
            ProgramComposition.Surface value = surfaces.get(i);
            ShaderDefinition coverage = value.definition().coverage();
            if (coverage == null) continue;
            source.append("            case ").append(i + 1).append("u: { input.implementationData = ")
                    .append("ShaderDataPtr<uint64_t>(input.shaderRoot.compositionData)[")
                    .append(dataOffsets.get(value.key())).append("u]; ").append(coverage.type())
                    .append(" value; return value.evaluateCoverage(input); }\n");
        }
        source.append("            default: { ErrorCoverage value; return value.evaluateCoverage(input); }\n")
                .append("        }\n    }\n};\n\n")
                .append("public struct VacuumVolume : IVolumeModel {};\n\n")
                .append("public struct VolumeDispatch : IVolumeDispatch {\n")
                .append("    public VolumeProperties evaluateVolume(uint implementation, VolumeInput input) {\n")
                .append("        switch (implementation) {\n");
        appendVolumeCases(source, volumes, dataOffsets, false);
        source.append("            default: { VacuumVolume value; return value.evaluateVolume(input); }\n")
                .append("        }\n    }\n\n")
                .append("    public float3 evaluateBoundaryLighting(uint implementation, VolumeBoundaryLightingInput input) {\n")
                .append("        switch (implementation) {\n");
        appendVolumeCases(source, volumes, dataOffsets, true);
        source.append("            default: { VacuumVolume value; return value.evaluateBoundaryLighting(input); }\n")
                .append("        }\n    }\n};\n\n")
                .append("public struct EnvironmentDispatch : IEnvironmentDispatch {\n")
                .append("    public float3 evaluateEnvironment(uint implementation, EnvironmentQuery query) {\n")
                .append("        switch (implementation) {\n")
                .append("            case 0u: { BuiltinEnvironment value; return value.evaluateEnvironment(query); }\n");
        for (int i = 0; i < environments.size(); i++) {
            source.append("            case ").append(i + 1).append("u: { ")
                    .append(environments.get(i).definition().implementation().type())
                    .append(" value; return value.evaluateEnvironment(query); }\n");
        }
        source.append("            default: { ErrorEnvironment value; return value.evaluateEnvironment(query); }\n")
                .append("        }\n    }\n};\n\n")
                .append("public struct Composition : IComposition {\n")
                .append("    public typealias Surfaces = SurfaceDispatch;\n")
                .append("    public typealias Coverages = CoverageDispatch;\n")
                .append("    public typealias Volumes = VolumeDispatch;\n")
                .append("    public typealias Environments = EnvironmentDispatch;\n};\n");
        return new GeneratedComposition(source.toString(), indices, data);
    }

    private static void appendVolumeCases(StringBuilder source, List<ProgramComposition.Volume> volumes,
                                          Map<ProgramKey, Integer> offsets, boolean lighting) {
        for (int i = 0; i < volumes.size(); i++) {
            ProgramComposition.Volume value = volumes.get(i);
            source.append("            case ").append(i + 1).append("u: { input")
                    .append(lighting ? ".volume" : "").append(".implementationData = ")
                    .append("ShaderDataPtr<uint64_t>(input").append(lighting ? ".volume" : "")
                    .append(".shaderRoot.compositionData)[").append(offsets.get(value.key())).append("u]; ")
                    .append(value.definition().implementation().type()).append(" value; return value.")
                    .append(lighting ? "evaluateBoundaryLighting" : "evaluateVolume").append("(input); }\n");
        }
    }

    private static java.util.stream.Stream<ShaderDefinition> definitions(
            ProgramComposition.Declaration declaration) {
        return switch (declaration) {
            case ProgramComposition.Surface surface -> surface.definition().coverage() == null
                    ? java.util.stream.Stream.of(surface.definition().surface())
                    : java.util.stream.Stream.of(surface.definition().surface(), surface.definition().coverage());
            case ProgramComposition.Volume volume -> java.util.stream.Stream.of(volume.definition().implementation());
            case ProgramComposition.Environment environment ->
                    java.util.stream.Stream.of(environment.definition().implementation());
        };
    }

    private static Map<String, ResolvedModule> resolveModules(
            List<ProgramComposition.Declaration> declarations) throws IOException {
        List<ShaderSource> sources = new ArrayList<>();
        Set<ShaderSource> identities = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        declarations.stream().flatMap(WorldShaderCompiler::definitions).map(ShaderDefinition::source)
                .forEach(source -> { if (identities.add(source)) sources.add(source); });
        Map<String, ResolvedModule> resolved = new LinkedHashMap<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (ProgramComposition.Declaration declaration : declarations) {
            for (ShaderDefinition definition : definitions(declaration).toList()) {
                resolveModule(definition.module(), definition.source(), sources, resolved, visiting);
            }
        }
        return resolved;
    }

    private static void resolveModule(String name, ShaderSource preferred, List<ShaderSource> sources,
                                      Map<String, ResolvedModule> resolved, Set<String> visiting)
            throws IOException {
        if (PROVIDED_MODULES.contains(name) || resolved.containsKey(name)) return;
        if (!visiting.add(name)) throw new IOException("cyclic extension shader import involving " + name);
        byte[] bytes = readModule(preferred, name);
        if (bytes == null) {
            for (ShaderSource source : sources) {
                bytes = readModule(source, name);
                if (bytes != null) break;
            }
        }
        if (bytes == null) throw new IOException("missing extension shader module " + name);
        for (ShaderSource source : sources) {
            byte[] candidate = readModule(source, name);
            if (candidate != null && !java.util.Arrays.equals(bytes, candidate)) {
                throw new IOException("ambiguous extension shader module " + name);
            }
        }
        resolved.put(name, new ResolvedModule(name, bytes));
        Matcher imports = IMPORT.matcher(new String(bytes, StandardCharsets.UTF_8));
        while (imports.find()) resolveModule(imports.group(1), preferred, sources, resolved, visiting);
        visiting.remove(name);
    }

    private static byte[] readModule(ShaderSource source, String name) throws IOException {
        try (InputStream input = source.openModule(name)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static void extractClasspath(String root, List<String> names, Path destination,
                                         String group, Map<String, byte[]> sources) throws IOException {
        Files.createDirectories(destination);
        for (String name : names) {
            try (InputStream input = WorldShaderCompiler.class.getResourceAsStream(root + name)) {
                if (input == null) throw new IOException("Missing bundled Slang source: " + root + name);
                byte[] bytes = input.readAllBytes();
                Files.write(destination.resolve(flatFileName(name)), bytes);
                sources.put(group + '/' + name, bytes);
            }
        }
    }

    private static void writeSpecializedModuleAlias(Path directory, String sourceFile, String module)
            throws IOException {
        Files.copy(directory.resolve(sourceFile), directory.resolve(module + ".slang"));
    }

    private static String flatFileName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    @SafeVarargs
    private static Set<String> moduleNames(List<String>... groups) {
        Set<String> names = new LinkedHashSet<>();
        for (List<String> group : groups) {
            for (String file : group) {
                String stem = flatFileName(file);
                stem = stem.substring(0, stem.length() - ".slang".length());
                names.add(stem.replace('.', '_'));
                names.add(stem);
            }
        }
        return Set.copyOf(names);
    }

    @Override
    public void close() {
        session.retainUntilProcessExit();
        if (cleanupDirectory != null) deleteDirectory(cleanupDirectory);
    }

    private static void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Could not delete temporary shader sources at {}", directory, e);
        }
    }

    private record ResolvedModule(String name, byte[] bytes) { }
    private record GeneratedComposition(String source, Map<ProgramKey, Integer> indices, List<Long> data) { }
}
