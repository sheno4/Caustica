package dev.comfyfluffy.caustica.rt.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slot;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.slang.SlangCompileResult;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Compiles world-pipeline entry points and specializes composition-generic stages at runtime. */
public final class WorldShaderCompiler implements AutoCloseable {
    public static final String SKY_MISS_MODULE = "sky_miss";
    public static final String CLOSEST_HIT_MODULE = "closest_hit";
    public static final String PRIMARY_MODULE = "primary_rgen";
    public static final String INDIRECT_MODULE = "indirect";
    public static final String INDIRECT_SER_MODULE = "indirect_ser";
    public static final String ENTRY_POINT = "main";

    /**
     * Descriptor set index reserved for pass-declared world-pipeline resources (e.g. SkyLutPass's own
     * sky-view/transmittance samplers) — the engine only reserves the set index itself; every binding
     * inside it is discovered from this composition's own reflection, never declared by the engine. See
     * {@link #passResourceBindings()}.
     */
    public static final int PASS_RESOURCE_SET = 2;

    private static final String WORLD_SHADER_ROOT = "/caustica/shaders/world/";
    private static final String API_ROOT = "/caustica/shaders/api/";
    private static final String COMPOSITION_MODULE = "caustica_composition";
    private static final String COMPOSITION_TYPE = "Composition";
    private static final String SURFACE_DISPATCH_TYPE = "SurfaceDispatch";
    private static final String SURFACE_MODIFIER_DISPATCH_TYPE = "SurfaceModifierDispatch";
    /**
     * Fixed, always-valid module name every composition-generic engine stage (e.g. {@code sky_miss.slang})
     * imports unconditionally. Its content is generated per composition from every selected feature's
     * {@code FeatureBuilder.passResourceModule} declarations — empty (still a valid, importable module) if
     * none declared any, so engine stages never need to know whether the current selection has any.
     */
    static final String PASS_RESOURCE_ANCHOR_MODULE = "caustica_pass_resources";
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");

    private static final List<String> WORLD_MODULES = List.of(
            "any_hit_common.slang", "radiance_any_hit.rahit.slang", "shadow_any_hit.rahit.slang",
            "bindings.slang", "closest_hit.slang",
            "guide.rmiss.slang", "guides.slang", "indirect.slang", "indirect_core.slang",
            "indirect_ser.slang", "lighting.slang", "math.slang", "medium.slang",
            "primary_rgen.slang", "segment.slang", "sky_miss.slang", "surface_bsdf.slang",
            "trace.slang", "trace_ordinary.slang", "trace_policy.slang", "trace_reordered.slang",
            "trace_ser.slang", "world_common.slang", "world_core.slang");
    private static final List<String> API_MODULES = List.of(
            "caustica_api.slang", "caustica_color.slang", "caustica_medium.slang", "caustica_sky.slang",
            "caustica_surface.slang", "caustica_surface_modifier.slang",
            "caustica_texture_resources.slang", "caustica_types.slang");
    private static final Set<String> ENGINE_MODULE_NAMES = moduleNames(WORLD_MODULES, API_MODULES);

    private final SlangSession session;
    private final Path worldDirectory;
    private final Path cleanupDirectory;
    private final Composition composition;
    private final Set<Integer> rejectedSurfaces;
    private final Map<String, byte[]> spirvByKey = new ConcurrentHashMap<>();
    /** Name → binding, accumulated from every compiled stage's reflection. See {@link #PASS_RESOURCE_SET}. */
    private final Map<String, PassResourceBinding> passResourceBindings = new LinkedHashMap<>();

    private WorldShaderCompiler(SlangSession session, Path worldDirectory, Path cleanupDirectory,
                                Composition composition, Set<Integer> rejectedSurfaces) {
        this.session = session;
        this.worldDirectory = worldDirectory;
        this.cleanupDirectory = cleanupDirectory;
        this.composition = composition;
        this.rejectedSurfaces = Set.copyOf(rejectedSurfaces);
    }

    public static WorldShaderCompiler create(Path cacheDirectory, CausticaRegistry.Selection selection)
            throws IOException {
        return create(cacheDirectory, selection, null);
    }

    /** Create an isolated source tree so abandoned and replacement runtime builds cannot overwrite each other. */
    public static WorldShaderCompiler createIsolated(Path cacheRoot, CausticaRegistry.Selection selection)
            throws IOException {
        Files.createDirectories(cacheRoot);
        Path directory = Files.createTempDirectory(cacheRoot, "runtime-");
        try {
            return create(directory, selection, directory);
        } catch (IOException | RuntimeException | Error e) {
            deleteDirectory(directory);
            throw e;
        }
    }

    private static WorldShaderCompiler create(Path cacheDirectory, CausticaRegistry.Selection selection,
                                              Path cleanupDirectory) throws IOException {
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        Objects.requireNonNull(selection, "selection");
        if (selection.surfaces().size() < 2) {
            throw new IllegalArgumentException(
                    "world shader compilation requires reference and error surface implementations");
        }
        Path worldDirectory = cacheDirectory.resolve("world");
        Path apiDirectory = cacheDirectory.resolve("api");
        Path featureDirectory = cacheDirectory.resolve("features");
        Path compositionDirectory = cacheDirectory.resolve("composition");

        Map<String, byte[]> sources = new LinkedHashMap<>();
        extractClasspath(WORLD_SHADER_ROOT, WORLD_MODULES, worldDirectory, "world", sources);
        extractClasspath(API_ROOT, API_MODULES, apiDirectory, "api", sources);

        List<Feature> selectedFeatures = selection.features();
        Map<String, ResolvedFeatureModule> extensionModules = resolveFeatureModules(selection, selectedFeatures);
        Map<Feature, Path> directories = new LinkedHashMap<>();
        for (Feature feature : selectedFeatures) {
            Path directory = featureDirectory.resolve(feature.id().namespace())
                    .resolve(feature.id().path());
            directories.put(feature, directory);
            Files.createDirectories(directory);
        }
        for (ResolvedFeatureModule resolved : extensionModules.values()) {
            Path destination = directories.get(resolved.feature()).resolve(resolved.module() + ".slang");
            Files.write(destination, resolved.bytes());
            sources.put("feature/" + resolved.feature().id() + '/' + resolved.module() + ".slang",
                    resolved.bytes());
        }

        Files.createDirectories(compositionDirectory);
        String anchorSource = passResourceAnchorModule(selectedFeatures);
        Files.writeString(compositionDirectory.resolve(PASS_RESOURCE_ANCHOR_MODULE + ".slang"), anchorSource,
                StandardCharsets.UTF_8);
        sources.put("generated/" + PASS_RESOURCE_ANCHOR_MODULE + ".slang",
                anchorSource.getBytes(StandardCharsets.UTF_8));
        writeSurfaceProbes(selection, compositionDirectory);
        writeSurfaceModifierProbes(selection, compositionDirectory);

        List<Path> searchPaths = new ArrayList<>();
        searchPaths.add(worldDirectory);
        searchPaths.add(apiDirectory);
        searchPaths.add(compositionDirectory);
        searchPaths.addAll(directories.values());
        SlangSession session = SlangRuntime.INSTANCE.openSession(searchPaths, false, true);

        // The root is generated only after every third-party implementation has proved it compiles.
        Set<Integer> rejectedSurfaces = rejectedSurfaces(session, selection);
        String rootSource = compositionRoot(selection, rejectedSurfaces,
                rejectedSurfaceModifiers(session, selection));
        Files.writeString(compositionDirectory.resolve(COMPOSITION_MODULE + ".slang"), rootSource,
                StandardCharsets.UTF_8);

        Composition composition = Composition.create(selection, COMPOSITION_MODULE, COMPOSITION_TYPE,
                rootSource, sources);
        return new WorldShaderCompiler(session, worldDirectory, cleanupDirectory, composition,
                rejectedSurfaces);
    }

    /**
     * One single-implementation composition per extension surface, used to compile that implementation
     * on its own. The renderer-owned reference and error surfaces occupy indices 0 and 1 and compile in
     * every real stage, so probing them would only cost two compiles.
     */
    private static void writeSurfaceProbes(CausticaRegistry.Selection selection, Path directory)
            throws IOException {
        List<Feature.SurfaceImplementation> surfaces = selection.surfaces();
        String skyModule = selection.binding(Slots.SKY).binding().module();
        String skyType = selection.binding(Slots.SKY).binding().type();
        for (int index = 2; index < surfaces.size(); index++) {
            Feature.SurfaceImplementation surface = surfaces.get(index);
            String source = "module " + surfaceProbeModule(index) + ";\n\n"
                    + "import caustica_api;\nimport caustica_types;\nimport caustica_surface;\n"
                    + "import caustica_surface_modifier;\n"
                    + "import " + skyModule + ";\n"
                    + (skyModule.equals(surface.module()) ? "" : "import " + surface.module() + ";\n")
                    + "\npublic struct ProbeSurfaces : ISurfaceDispatch {\n"
                    + "    public void evaluateSurface(uint implementation, SurfaceInput input,\n"
                    + "            inout MaterialInput material) {\n"
                    + "        " + surface.type() + " s; s.evaluateSurface(input, material);\n    }\n\n"
                    + "    public float3 evaluateResponse(uint implementation, float3 radiance,\n"
                    + "            SurfaceClosure surface) {\n"
                    + "        " + surface.type() + " s; return s.evaluateResponse(radiance, surface);\n"
                    + "    }\n\n"
                    + "    public MediumProperties evaluateMedium(uint implementation, MediumInput input) {\n"
                    + "        " + surface.type() + " s; return s.evaluateMedium(input);\n"
                    + "    }\n\n"
                    + "    public float3 evaluateMediumLighting(uint implementation, MediumLightingInput input) {\n"
                    + "        " + surface.type() + " s; return s.evaluateMediumLighting(input);\n"
                    + "    }\n};\n\n"
                    + noOpModifierDispatch("ProbeSurfaceModifiers")
                    + "public struct ProbeComposition : IComposition {\n"
                    + "    public typealias Sky = " + skyType + ";\n"
                    + "    public typealias Surfaces = ProbeSurfaces;\n"
                    + "    public typealias SurfaceModifiers = ProbeSurfaceModifiers;\n};\n";
            Files.writeString(directory.resolve(surfaceProbeModule(index) + ".slang"), source,
                    StandardCharsets.UTF_8);
        }
    }

    /**
     * Indices whose implementation does not compile. They keep their index — renumbering would silently
     * repoint every material compiled against the old order — and the generated switch resolves them to
     * the error surface instead.
     */
    private static Set<Integer> rejectedSurfaces(SlangSession session,
                                                 CausticaRegistry.Selection selection) {
        List<Feature.SurfaceImplementation> surfaces = selection.surfaces();
        Set<Integer> rejected = new LinkedHashSet<>();
        for (int index = 2; index < surfaces.size(); index++) {
            Feature.SurfaceImplementation surface = surfaces.get(index);
            try {
                // Specializing a real stage, not just compiling the module: only this reaches the rules
                // that apply to a type used as a composition's implementation, such as the ban on
                // declaring global shader parameters.
                session.compileSpecialized(CLOSEST_HIT_MODULE, ENTRY_POINT,
                        surfaceProbeModule(index), "ProbeComposition");
            } catch (RuntimeException e) {
                CausticaMod.LOGGER.error("Surface implementation {} from {} does not compile; materials "
                        + "naming it render as the error surface", surface.id(), surface.featureId(), e);
                rejected.add(index);
            }
        }
        return rejected;
    }

    private static String surfaceProbeModule(int index) {
        return "caustica_surface_probe_" + index;
    }

    /** One isolated composition per modifier, so a broken extension cannot reject the whole pipeline. */
    private static void writeSurfaceModifierProbes(CausticaRegistry.Selection selection, Path directory)
            throws IOException {
        List<Feature.SurfaceModifierImplementation> modifiers = selection.surfaceModifiers();
        Feature.SurfaceImplementation referenceSurface = selection.surfaces().get(0);
        String skyModule = selection.binding(Slots.SKY).binding().module();
        String skyType = selection.binding(Slots.SKY).binding().type();
        for (int index = 0; index < modifiers.size(); index++) {
            Feature.SurfaceModifierImplementation modifier = modifiers.get(index);
            String source = "module " + surfaceModifierProbeModule(index) + ";\n\n"
                    + "import caustica_api;\nimport caustica_types;\nimport caustica_surface;\n"
                    + "import caustica_surface_modifier;\n"
                    + "import " + skyModule + ";\n"
                    + (skyModule.equals(referenceSurface.module()) ? ""
                    : "import " + referenceSurface.module() + ";\n")
                    + (skyModule.equals(modifier.module())
                    || referenceSurface.module().equals(modifier.module()) ? ""
                    : "import " + modifier.module() + ";\n")
                    + referenceSurfaceDispatch(referenceSurface.type())
                    + "public struct ProbeSurfaceModifiers : ISurfaceModifierDispatch {\n"
                    + "    public void applySurfaceModifiers(SurfaceModifierInput input,\n"
                    + "            inout MaterialInput material) {\n"
                    + "        " + modifier.type() + " m; m.applySurfaceModifier(input, material);\n"
                    + "    }\n};\n\n"
                    + "public struct ProbeComposition : IComposition {\n"
                    + "    public typealias Sky = " + skyType + ";\n"
                    + "    public typealias Surfaces = ProbeSurfaces;\n"
                    + "    public typealias SurfaceModifiers = ProbeSurfaceModifiers;\n};\n";
            Files.writeString(directory.resolve(surfaceModifierProbeModule(index) + ".slang"), source,
                    StandardCharsets.UTF_8);
        }
    }

    private static Set<Integer> rejectedSurfaceModifiers(SlangSession session,
                                                         CausticaRegistry.Selection selection) {
        List<Feature.SurfaceModifierImplementation> modifiers = selection.surfaceModifiers();
        Set<Integer> rejected = new LinkedHashSet<>();
        for (int index = 0; index < modifiers.size(); index++) {
            Feature.SurfaceModifierImplementation modifier = modifiers.get(index);
            try {
                session.compileSpecialized(CLOSEST_HIT_MODULE, ENTRY_POINT,
                        surfaceModifierProbeModule(index), "ProbeComposition");
            } catch (RuntimeException e) {
                CausticaMod.LOGGER.error("Surface modifier {} from {} does not compile and was omitted",
                        modifier.id(), modifier.featureId(), e);
                rejected.add(index);
            }
        }
        return rejected;
    }

    private static String surfaceModifierProbeModule(int index) {
        return "caustica_surface_modifier_probe_" + index;
    }

    private static String noOpModifierDispatch(String type) {
        return "public struct " + type + " : ISurfaceModifierDispatch {\n"
                + "    public void applySurfaceModifiers(SurfaceModifierInput input,\n"
                + "            inout MaterialInput material) {}\n};\n\n";
    }

    private static String referenceSurfaceDispatch(String type) {
        return "public struct ProbeSurfaces : ISurfaceDispatch {\n"
                + "    public void evaluateSurface(uint implementation, SurfaceInput input,\n"
                + "            inout MaterialInput material) { " + type
                + " s; s.evaluateSurface(input, material); }\n"
                + "    public float3 evaluateResponse(uint implementation, float3 radiance,\n"
                + "            SurfaceClosure surface) { " + type
                + " s; return s.evaluateResponse(radiance, surface); }\n"
                + "    public MediumProperties evaluateMedium(uint implementation, MediumInput input) { "
                + type + " s; return s.evaluateMedium(input); }\n"
                + "    public float3 evaluateMediumLighting(uint implementation, MediumLightingInput input) { "
                + type + " s; return s.evaluateMediumLighting(input); }\n};\n\n";
    }

    public Composition composition() {
        return composition;
    }

    public Set<Integer> rejectedSurfaces() {
        return rejectedSurfaces;
    }

    public byte[] compileSpecialized(String engineModule, String entryPoint) {
        String key = "composition:" + composition.contentHash() + '/' + engineModule + '/' + entryPoint;
        return cached(key, () -> {
            SlangCompileResult result = session.compileSpecialized(
                    engineModule, entryPoint, composition.rootModule(), composition.rootType());
            collectPassResourceBindings(engineModule, result.reflectionJson());
            return result.spirv();
        }, engineModule + ':' + entryPoint + " for " + composition.contentHash().substring(0, 12));
    }

    /**
     * Every {@code space == }{@link #PASS_RESOURCE_SET} binding reflected across every composition-generic
     * stage compiled so far — call after compiling every stage the pipeline needs (a partial view before
     * that would silently miss a binding only reachable from a stage not yet compiled). Empty if no
     * currently selected feature declares any.
     */
    public synchronized Map<String, PassResourceBinding> passResourceBindings() {
        Set<Integer> indices = new java.util.TreeSet<>();
        passResourceBindings.values().forEach(binding -> indices.add(binding.index()));
        for (int expected = 0; expected < indices.size(); expected++) {
            if (!indices.contains(expected)) {
                throw new IllegalStateException("world pass resource set " + PASS_RESOURCE_SET
                        + " is not contiguous: " + passResourceBindings);
            }
        }
        return Map.copyOf(passResourceBindings);
    }

    /** What kind of Vulkan descriptor a reflected {@code space == PASS_RESOURCE_SET} binding needs. */
    public enum PassResourceKind {
        SAMPLED_IMAGE, STORAGE_IMAGE, STORAGE_BUFFER, UNIFORM_BUFFER
    }

    public record PassResourceBinding(int index, PassResourceKind kind) {
    }

    /** Package-private (rather than private) so tests can exercise the collision/contiguity checks directly. */
    synchronized void collectPassResourceBindings(String stage, String reflectionJson) {
        JsonObject reflection = JsonParser.parseString(reflectionJson).getAsJsonObject();
        JsonArray parameters = reflection.getAsJsonArray("parameters");
        if (parameters == null) {
            return;
        }
        for (var element : parameters) {
            JsonObject parameter = element.getAsJsonObject();
            JsonObject binding = parameter.has("binding") ? parameter.getAsJsonObject("binding") : null;
            if (binding == null || !"descriptorTableSlot".equals(binding.get("kind").getAsString())) {
                continue;
            }
            int space = binding.has("space") ? binding.get("space").getAsInt() : 0;
            if (space != PASS_RESOURCE_SET) {
                continue;
            }
            String name = parameter.get("name").getAsString();
            int index = binding.get("index").getAsInt();
            PassResourceKind kind = passResourceKind(name, parameter.getAsJsonObject("type"));
            PassResourceBinding existing = passResourceBindings.get(name);
            if (existing != null) {
                if (existing.index() != index || existing.kind() != kind) {
                    throw new IllegalStateException("world pass resource '" + name + "' reflects as "
                            + existing.index() + '/' + existing.kind() + " in an earlier stage but "
                            + index + '/' + kind + " in " + stage);
                }
                continue;
            }
            for (Map.Entry<String, PassResourceBinding> other : passResourceBindings.entrySet()) {
                if (other.getValue().index() == index) {
                    throw new IllegalStateException("world pass resources '" + name + "' and '"
                            + other.getKey() + "' both claim set " + PASS_RESOURCE_SET + " index " + index);
                }
            }
            passResourceBindings.put(name, new PassResourceBinding(index, kind));
        }
    }

    /**
     * Maps a reflected parameter's {@code type} to a {@link PassResourceKind}. Shape confirmed empirically
     * against {@code slangc -reflection-json} (not documented anywhere authoritative): a plain resource
     * has {@code type.kind == "resource"}, distinguished by {@code baseShape} ({@code "texture2D"} vs
     * {@code "structuredBuffer"}) and, for images only, {@code combined} (sampled) vs {@code access ==
     * "readWrite"} (storage) — {@code StructuredBuffer}/{@code RWStructuredBuffer} both reflect as
     * {@code structuredBuffer} and both lower to {@code VK_DESCRIPTOR_TYPE_STORAGE_BUFFER} regardless of
     * {@code access}, since Vulkan has no separate read-only storage-buffer descriptor type. A
     * {@code ConstantBuffer<T>} is its own top-level {@code type.kind == "constantBuffer"}, not nested
     * under {@code "resource"} at all.
     */
    private static PassResourceKind passResourceKind(String name, JsonObject type) {
        String kind = type.get("kind").getAsString();
        if ("constantBuffer".equals(kind)) {
            return PassResourceKind.UNIFORM_BUFFER;
        }
        if (!"resource".equals(kind)) {
            throw new IllegalStateException("world pass resource '" + name
                    + "' has an unsupported type (" + kind + ") — only samplers, storage images, "
                    + "StructuredBuffer/RWStructuredBuffer, and ConstantBuffer are supported at set "
                    + PASS_RESOURCE_SET);
        }
        String baseShape = type.has("baseShape") ? type.get("baseShape").getAsString() : "";
        if ("structuredBuffer".equals(baseShape)) {
            return PassResourceKind.STORAGE_BUFFER;
        }
        boolean combined = type.has("combined") && type.get("combined").getAsBoolean();
        return combined ? PassResourceKind.SAMPLED_IMAGE : PassResourceKind.STORAGE_IMAGE;
    }

    public byte[] compileSkyMiss() {
        return compileSpecialized(SKY_MISS_MODULE, ENTRY_POINT);
    }

    public byte[] compileClosestHit() {
        return compileSpecialized(CLOSEST_HIT_MODULE, ENTRY_POINT);
    }

    public byte[] compilePrimary() {
        return compileSpecialized(PRIMARY_MODULE, ENTRY_POINT);
    }

    public byte[] compileIndirect(boolean reordered) {
        return compileSpecialized(reordered ? INDIRECT_SER_MODULE : INDIRECT_MODULE, ENTRY_POINT);
    }

    public byte[] compilePlain(String moduleFileName, String entryPoint) {
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
        return spirvByKey.computeIfAbsent(key, ignored -> {
            long startNanos = System.nanoTime();
            byte[] spirv = compile.get();
            CausticaMod.LOGGER.info(String.format(Locale.ROOT,
                    "Compiled world shader %s in %.1f ms (%d bytes SPIR-V)", label,
                    (System.nanoTime() - startNanos) / 1.0e6, spirv.length));
            return spirv;
        });
    }

    @Override
    public void close() {
        session.retainUntilProcessExit();
        if (cleanupDirectory != null) {
            deleteDirectory(cleanupDirectory);
        }
    }

    private static void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Could not delete temporary shader sources at {}", directory, e);
        }
    }

    private static Map<String, ResolvedFeatureModule> resolveFeatureModules(
            CausticaRegistry.Selection selection, List<Feature> selectedFeatures) throws IOException {
        Map<String, ResolvedFeatureModule> resolved = new LinkedHashMap<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (CausticaRegistry.SelectedBinding selected : selection.bindings().values()) {
            resolveFeatureModule(selected.binding().module(), selectedFeatures, resolved, visiting);
        }
        for (Feature.SurfaceImplementation surface : selection.surfaces()) {
            resolveFeatureModule(surface.module(), selectedFeatures, resolved, visiting);
        }
        for (Feature.SurfaceModifierImplementation modifier : selection.surfaceModifiers()) {
            resolveFeatureModule(modifier.module(), selectedFeatures, resolved, visiting);
        }
        // A feature's passResourceModule may not be reachable from any slot-bound module's own imports
        // (e.g. caustica:builtin is still selected via SURFACE even when a different feature wins
        // SKY) — resolve these as additional roots so passResourceAnchorModule's imports always resolve.
        for (Feature feature : selectedFeatures) {
            for (String module : feature.passResourceModules()) {
                resolveFeatureModule(module, selectedFeatures, resolved, visiting);
            }
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

    /**
     * Generates the composition root: the slot aliases, plus the per-material surface dispatch as a
     * literal switch over every registered implementation. The engine never names an extension
     * implementation: a material carries its index. Index 0 explicitly preserves the built-in reference
     * surface, while rejected and out-of-range indices resolve through the default error surface.
     */
    private static String compositionRoot(CausticaRegistry.Selection selection,
                                          Set<Integer> rejectedSurfaces,
                                          Set<Integer> rejectedModifiers) {
        StringBuilder source = new StringBuilder("module ").append(COMPOSITION_MODULE)
                .append(";\n\nimport caustica_api;\nimport caustica_types;\nimport caustica_surface;\n")
                .append("import caustica_surface_modifier;\n");
        List<Feature.SurfaceImplementation> surfaces = selection.surfaces();
        List<Feature.SurfaceModifierImplementation> modifiers = selection.surfaceModifiers();
        Stream.of(selection.bindings().values().stream().map(selected -> selected.binding().module()),
                        java.util.stream.IntStream.range(0, surfaces.size())
                                .filter(index -> !rejectedSurfaces.contains(index))
                                .mapToObj(index -> surfaces.get(index).module()),
                        java.util.stream.IntStream.range(0, modifiers.size())
                                .filter(index -> !rejectedModifiers.contains(index))
                                .mapToObj(index -> modifiers.get(index).module()))
                .flatMap(stream -> stream)
                .distinct().sorted().forEach(module -> source.append("import ").append(module).append(";\n"));

        source.append("\npublic struct ").append(SURFACE_DISPATCH_TYPE).append(" : ISurfaceDispatch {\n")
                .append("    public void evaluateSurface(uint implementation, SurfaceInput input,\n")
                .append("            inout MaterialInput material) {\n")
                .append("        switch (implementation) {\n");
        appendSurfaceCases(source, selection, rejectedSurfaces,
                type -> "{ " + type + " s; s.evaluateSurface(input, material); return; }");
        source.append("        }\n    }\n\n")
                .append("    public float3 evaluateResponse(uint implementation, float3 radiance,\n")
                .append("            SurfaceClosure surface) {\n")
                .append("        switch (implementation) {\n");
        appendSurfaceCases(source, selection, rejectedSurfaces,
                type -> "{ " + type + " s; return s.evaluateResponse(radiance, surface); }");
        source.append("        }\n    }\n\n")
                .append("    public MediumProperties evaluateMedium(uint implementation, MediumInput input) {\n")
                .append("        switch (implementation) {\n");
        appendSurfaceCases(source, selection, rejectedSurfaces,
                type -> "{ " + type + " s; return s.evaluateMedium(input); }");
        source.append("        }\n    }\n\n")
                .append("    public float3 evaluateMediumLighting(uint implementation, MediumLightingInput input) {\n")
                .append("        switch (implementation) {\n");
        appendSurfaceCases(source, selection, rejectedSurfaces,
                type -> "{ " + type + " s; return s.evaluateMediumLighting(input); }");
        source.append("        }\n    }\n};\n");

        source.append("\npublic struct ").append(SURFACE_MODIFIER_DISPATCH_TYPE)
                .append(" : ISurfaceModifierDispatch {\n")
                .append("    public void applySurfaceModifiers(SurfaceModifierInput input,\n")
                .append("            inout MaterialInput material) {\n");
        for (int index = 0; index < modifiers.size(); index++) {
            if (rejectedModifiers.contains(index)) {
                continue;
            }
            source.append("        { ").append(modifiers.get(index).type())
                    .append(" m; m.applySurfaceModifier(input, material); }\n");
        }
        source.append("    }\n};\n");

        source.append("\npublic struct ").append(COMPOSITION_TYPE).append(" : IComposition {\n");
        appendAlias(source, "Sky", selection.binding(Slots.SKY).binding().type());
        appendAlias(source, "Surfaces", SURFACE_DISPATCH_TYPE);
        appendAlias(source, "SurfaceModifiers", SURFACE_MODIFIER_DISPATCH_TYPE);
        return source.append("};\n").toString();
    }

    private static void appendSurfaceCases(StringBuilder source, CausticaRegistry.Selection selection,
                                           Set<Integer> rejectedSurfaces, UnaryOperator<String> body) {
        List<Feature.SurfaceImplementation> surfaces = selection.surfaces();
        source.append("        case 0u: ").append(body.apply(surfaces.get(0).type())).append('\n');
        for (int index = 2; index < surfaces.size(); index++) {
            if (rejectedSurfaces.contains(index)) {
                continue;
            }
            source.append("        case ").append(index).append("u: ")
                    .append(body.apply(surfaces.get(index).type())).append('\n');
        }
        source.append("        default: ").append(body.apply(surfaces.get(1).type())).append('\n');
    }

    private static void appendAlias(StringBuilder source, String name, String type) {
        source.append("    public typealias ").append(name).append(" = ").append(type).append(";\n");
    }

    /**
     * Generates {@link #PASS_RESOURCE_ANCHOR_MODULE}: a plain (non-generic) import of every selected
     * feature's {@code passResourceModule} declarations. An engine stage imports this fixed name
     * unconditionally instead of any specific pass's module — see {@code FeatureBuilder.passResourceModule}
     * for why a plain import is required at all, and {@code sky_miss.slang} for the consumer.
     */
    private static String passResourceAnchorModule(List<Feature> selectedFeatures) {
        StringBuilder source = new StringBuilder("module ").append(PASS_RESOURCE_ANCHOR_MODULE).append(";\n");
        selectedFeatures.stream().flatMap(feature -> feature.passResourceModules().stream())
                .distinct().sorted().forEach(module -> source.append("import ").append(module).append(";\n"));
        return source.toString();
    }

    /**
     * Extracts each classpath resource under {@code root} (which may carry a source-side subdirectory,
     * for on-disk organization) flat into {@code destination} by its bare
     * filename — Slang's own search path stays a single flat directory regardless of how the source tree
     * is organized, so only the classpath lookup needs the subdirectory, never the extracted layout.
     */
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
                Files.write(destination.resolve(flatFileName(name)), bytes);
                sources.put(sourceGroup + '/' + name, bytes);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Missing bundled Slang source(s): " + String.join(", ", missing));
        }
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

    private record ResolvedFeatureModule(Feature feature, String module, byte[] bytes) {
    }
}
