package dev.comfyfluffy.caustica.rt.shader;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slot;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles world-pipeline entry points and specializes composition-generic stages at runtime. */
public final class WorldShaderCompiler implements AutoCloseable {
    public static final String SKY_MISS_MODULE = "sky_miss";
    public static final String CLOSEST_HIT_MODULE = "closest_hit";
    public static final String INDIRECT_MODULE = "indirect";
    public static final String INDIRECT_SER_MODULE = "indirect_ser";
    public static final String ENTRY_POINT = "main";

    private static final String WORLD_SHADER_ROOT = "/caustica/shaders/world/";
    private static final String API_ROOT = "/caustica/shaders/api/";
    private static final String COMPOSITION_MODULE = "caustica_composition";
    private static final String COMPOSITION_TYPE = "Composition";
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");

    private static final List<String> WORLD_MODULES = List.of(
            "any_hit.rahit.slang", "bindings.slang", "closest_hit.slang", "frame.slang",
            "guide.rmiss.slang", "guides.slang", "indirect.slang", "indirect_core.slang",
            "indirect_ser.slang", "lighting.slang", "math.slang", "medium.slang",
            "primary.rgen.slang", "segment.slang", "sky.slang", "sky_miss.slang",
            "trace.slang", "trace_ordinary.slang", "trace_policy.slang", "trace_reordered.slang",
            "trace_ser.slang", "water.slang", "world_common.slang", "world_core.slang");
    private static final List<String> API_MODULES = List.of(
            "caustica_api.slang", "caustica_medium.slang", "caustica_sky.slang",
            "caustica_surface.slang", "caustica_types.slang");
    private static final Set<String> ENGINE_MODULE_NAMES = moduleNames(WORLD_MODULES, API_MODULES);

    private final SlangSession session;
    private final Path worldDirectory;
    private final Composition composition;
    private final Map<String, byte[]> spirvByKey = new HashMap<>();

    private WorldShaderCompiler(SlangSession session, Path worldDirectory, Composition composition) {
        this.session = session;
        this.worldDirectory = worldDirectory;
        this.composition = composition;
    }

    public static WorldShaderCompiler create(Path cacheDirectory, CausticaRegistry.Selection selection)
            throws IOException {
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        Objects.requireNonNull(selection, "selection");
        Path worldDirectory = cacheDirectory.resolve("world");
        Path apiDirectory = cacheDirectory.resolve("api");
        Path featureDirectory = cacheDirectory.resolve("features");
        Path compositionDirectory = cacheDirectory.resolve("composition");

        Map<String, byte[]> sources = new LinkedHashMap<>();
        extractClasspath(WORLD_SHADER_ROOT, WORLD_MODULES, worldDirectory, "world", sources);
        extractClasspath(API_ROOT, API_MODULES, apiDirectory, "api", sources);

        List<Feature> selectedFeatures = selection.bindings().values().stream()
                .map(CausticaRegistry.SelectedBinding::feature).distinct().toList();
        Map<String, ResolvedFeatureModule> extensionModules = resolveFeatureModules(selection, selectedFeatures);
        Map<Feature, Path> directories = new LinkedHashMap<>();
        for (Feature feature : selectedFeatures) {
            Path directory = featureDirectory.resolve(feature.id().getNamespace())
                    .resolve(feature.id().getPath());
            directories.put(feature, directory);
            Files.createDirectories(directory);
        }
        for (ResolvedFeatureModule resolved : extensionModules.values()) {
            Path destination = directories.get(resolved.feature()).resolve(resolved.module() + ".slang");
            Files.write(destination, resolved.bytes());
            sources.put("feature/" + resolved.feature().id() + '/' + resolved.module() + ".slang",
                    resolved.bytes());
        }

        String rootSource = compositionRoot(selection);
        Files.createDirectories(compositionDirectory);
        Files.writeString(compositionDirectory.resolve(COMPOSITION_MODULE + ".slang"), rootSource,
                StandardCharsets.UTF_8);
        Composition composition = Composition.create(selection, COMPOSITION_MODULE, COMPOSITION_TYPE,
                rootSource, sources);

        List<Path> searchPaths = new ArrayList<>();
        searchPaths.add(worldDirectory);
        searchPaths.add(apiDirectory);
        searchPaths.add(compositionDirectory);
        searchPaths.addAll(directories.values());
        SlangSession session = SlangRuntime.INSTANCE.openSession(searchPaths, false, true);
        return new WorldShaderCompiler(session, worldDirectory, composition);
    }

    public Composition composition() {
        return composition;
    }

    public synchronized byte[] compileSpecialized(String engineModule, String entryPoint) {
        String key = "composition:" + composition.contentHash() + '/' + engineModule + '/' + entryPoint;
        return cached(key, () -> session.compileSpecialized(engineModule, entryPoint,
                        composition.rootModule(), composition.rootType()).spirv(),
                engineModule + ':' + entryPoint + " for " + composition.contentHash().substring(0, 12));
    }

    public byte[] compileSkyMiss() {
        return compileSpecialized(SKY_MISS_MODULE, ENTRY_POINT);
    }

    public byte[] compileClosestHit() {
        return compileSpecialized(CLOSEST_HIT_MODULE, ENTRY_POINT);
    }

    public byte[] compileIndirect(boolean reordered) {
        return compileSpecialized(reordered ? INDIRECT_SER_MODULE : INDIRECT_MODULE, ENTRY_POINT);
    }

    public synchronized byte[] compilePlain(String moduleFileName, String entryPoint) {
        Objects.requireNonNull(moduleFileName, "moduleFileName");
        Objects.requireNonNull(entryPoint, "entryPoint");
        String key = "plain:" + moduleFileName + '/' + entryPoint;
        return cached(key, () -> {
            Path file = worldDirectory.resolve(moduleFileName);
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String base = moduleFileName.endsWith(".slang")
                        ? moduleFileName.substring(0, moduleFileName.length() - ".slang".length())
                        : moduleFileName;
                return session.compile(base.replace('.', '_'), file.toString(), source, entryPoint).spirv();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + file + " for runtime compilation", e);
            }
        }, moduleFileName + ':' + entryPoint);
    }

    private byte[] cached(String key, Supplier<byte[]> compile, String label) {
        byte[] existing = spirvByKey.get(key);
        if (existing != null) {
            return existing;
        }
        long startNanos = System.nanoTime();
        byte[] spirv = compile.get();
        spirvByKey.put(key, spirv);
        CausticaMod.LOGGER.info(String.format(Locale.ROOT,
                "Compiled world shader %s in %.1f ms (%d bytes SPIR-V)", label,
                (System.nanoTime() - startNanos) / 1.0e6, spirv.length));
        return spirv;
    }

    @Override
    public void close() {
        session.close();
    }

    private static Map<String, ResolvedFeatureModule> resolveFeatureModules(
            CausticaRegistry.Selection selection, List<Feature> selectedFeatures) throws IOException {
        Map<String, ResolvedFeatureModule> resolved = new LinkedHashMap<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (CausticaRegistry.SelectedBinding selected : selection.bindings().values()) {
            resolveFeatureModule(selected.binding().module(), selectedFeatures, resolved, visiting);
        }
        return resolved;
    }

    private static void resolveFeatureModule(String module, List<Feature> selectedFeatures,
                                             Map<String, ResolvedFeatureModule> resolved,
                                             Set<String> visiting) throws IOException {
        if (ENGINE_MODULE_NAMES.contains(module) || resolved.containsKey(module)) {
            return;
        }
        if (!visiting.add(module)) {
            throw new IOException("cyclic extension shader import involving " + module);
        }
        ResolvedFeatureModule found = null;
        for (Feature feature : selectedFeatures) {
            ShaderSource source = feature.shaderSource();
            try (InputStream input = source.openModule(module)) {
                if (input == null) {
                    continue;
                }
                byte[] bytes = input.readAllBytes();
                if (found != null && !java.util.Arrays.equals(found.bytes(), bytes)) {
                    throw new IOException("ambiguous extension shader module " + module + " in "
                            + found.feature().id() + " and " + feature.id());
                }
                found = new ResolvedFeatureModule(feature, module, bytes);
            }
        }
        if (found == null) {
            throw new IOException("missing extension shader module " + module);
        }
        resolved.put(module, found);
        Matcher imports = IMPORT.matcher(new String(found.bytes(), StandardCharsets.UTF_8));
        while (imports.find()) {
            resolveFeatureModule(imports.group(1), selectedFeatures, resolved, visiting);
        }
        visiting.remove(module);
    }

    private static String compositionRoot(CausticaRegistry.Selection selection) {
        StringBuilder source = new StringBuilder("module ").append(COMPOSITION_MODULE)
                .append(";\n\nimport caustica_api;\n");
        selection.bindings().values().stream().map(selected -> selected.binding().module())
                .distinct().sorted().forEach(module -> source.append("import ").append(module).append(";\n"));
        source.append("\npublic struct ").append(COMPOSITION_TYPE).append(" : IComposition {\n");
        appendAlias(source, "Sky", selection.binding(Slots.SKY));
        appendAlias(source, "Surface", selection.binding(Slots.SURFACE));
        appendAlias(source, "Medium", selection.binding(Slots.MEDIUM));
        return source.append("};\n").toString();
    }

    private static void appendAlias(StringBuilder source, String name,
                                    CausticaRegistry.SelectedBinding selected) {
        source.append("    public typealias ").append(name).append(" = ")
                .append(selected.binding().type()).append(";\n");
    }

    private static void extractClasspath(String root, List<String> names, Path destination,
                                         String sourceGroup, Map<String, byte[]> sources) throws IOException {
        Files.createDirectories(destination);
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            String resource = root + name;
            try (InputStream input = WorldShaderCompiler.class.getResourceAsStream(resource)) {
                if (input == null) {
                    missing.add(resource);
                    continue;
                }
                byte[] bytes = input.readAllBytes();
                Files.write(destination.resolve(name), bytes);
                sources.put(sourceGroup + '/' + name, bytes);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Missing bundled Slang source(s): " + String.join(", ", missing));
        }
    }

    @SafeVarargs
    private static Set<String> moduleNames(List<String>... groups) {
        Set<String> names = new LinkedHashSet<>();
        for (List<String> group : groups) {
            for (String file : group) {
                names.add(file.substring(0, file.length() - ".slang".length()).replace('.', '_'));
                names.add(file.substring(0, file.length() - ".slang".length()));
            }
        }
        return Set.copyOf(names);
    }

    private record ResolvedFeatureModule(Feature feature, String module, byte[] bytes) {
    }
}
