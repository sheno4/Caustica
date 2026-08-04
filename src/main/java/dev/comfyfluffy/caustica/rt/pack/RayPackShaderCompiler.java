package dev.comfyfluffy.caustica.rt.pack;

import dev.comfyfluffy.caustica.CausticaMod;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Compiles world-pipeline ray-tracing entry points at runtime instead of at build time
 * (docs/EXTENSION_API.md section 7). This is the single place Slang is invoked for the world
 * pipeline — pipeline creation asks for SPIR-V and never talks to the compiler itself.
 *
 * <p>Two distinct things share this compiler:
 * <ul>
 *   <li>{@link #compileSpecialized}: an engine entry point generic over {@code TPack}, specialized with
 *       the active epoch's concrete pack type. {@code pack_sky_miss.slang} routes the sky; {@code
 *       pack_closest_hit.slang}/{@code pack_indirect.slang} (docs/EXTENSION_API.md, the surface slice) route
 *       the opaque terrain/entity surface model and BSDF. None of the three share a filename with a
 *       build-time stage — see pack_sky_miss.slang's "no stage infix" convention.
 *   <li>{@link #compilePlain}: an ordinary world-pipeline stage (primary/indirect/guide/closest-hit/
 *       any-hit/sky.rmiss) compiled exactly as build time would, just through this runtime path instead —
 *       no pack type involved. This proves the runtime-compilation machinery itself (source extraction,
 *       import resolution, SPIR-V validation) independently of whether any pack code actually runs, which
 *       is the point of the {@code caustica.rt.dynamicWorldShaders} slice: content is unchanged, only
 *       *when* it compiles moves.
 * </ul>
 *
 * <p>World-pipeline and pack sources ship as classpath resources, but Slang resolves {@code import}
 * through filesystem search paths, so the required source trees are extracted once into a cache directory
 * and the session is pointed at that. An externally installed pack would instead contribute its own
 * already-on-disk source root.
 *
 * <p>Results are cached in memory. The cache key deliberately does NOT yet include the compiler version,
 * module hashes, or a capability plan, so it is only valid within a single run — it is not the persistent
 * on-disk cache described in section 8.1.
 */
public final class RayPackShaderCompiler implements AutoCloseable {
    /** Engine-owned entry point: the sky miss shader, generic over the pack's IRayPack implementation. */
    public static final String SKY_MISS_MODULE = "pack_sky_miss";
    /** Engine-owned entry point: closest-hit, generic over the pack's surface model (Phase B). */
    public static final String CLOSEST_HIT_MODULE = "pack_closest_hit";
    /** Engine-owned entry point: indirect raygen, generic over the pack's BSDF/look (Phase B). */
    public static final String INDIRECT_MODULE = "pack_indirect";
    /** Every world-pipeline stage uses this entry point name (see RtPipeline.create's fixed "main"). */
    public static final String ENTRY_POINT = "main";

    private static final String WORLD_SHADER_ROOT = "/caustica/shadersrc/pipelines/world/";
    private static final String PACK_API_ROOT = "/caustica/raypacks/api/0.1/";
    private static final String BUNDLED_PACK_ROOT = "/caustica/raypacks/default/shaders/";

    // Every world-pipeline module needed to plain-compile primary.rgen/indirect.rgen/guide.rmiss/
    // closest_hit.rchit/any_hit.rahit/sky.rmiss, plus pack_sky_miss's own imports. Listed explicitly
    // rather than by scanning the jar so an unexpected addition is a visible change here, not a silent
    // one. Deliberately excludes:
    //   - trace_ser.slang: only reached through indirect.rgen's #ifdef CAUSTICA_ENABLE_EXT_SER branch,
    //     which this compiler never defines (the shim accepts neither preprocessor defines nor capability
    //     flags — see SlangSerCapabilityTest). The SER-reordered raygen stays build-time only until that
    //     branch is split into its own module.
    //   - shadow.rmiss.slang: not bound into any pipeline today (dead in the build-time path too).
    private static final List<String> WORLD_MODULES = List.of(
            "any_hit.rahit.slang", "bindings.slang", "closest_hit.rchit.slang", "guide.rmiss.slang",
            "guides.slang", "indirect.rgen.slang", "lighting.slang", "math.slang", "medium.slang",
            "pack_closest_hit.slang", "pack_frame.slang", "pack_indirect.slang", "pack_sky_miss.slang",
            "primary.rgen.slang", "segment.slang", "sky.rmiss.slang", "sky.slang",
            "trace.slang", "water.slang", "world_common.slang", "world_core.slang");
    private static final List<String> PACK_API_MODULES = List.of("caustica_ray_pack_api.slang");
    private static final List<String> BUNDLED_PACK_MODULES = List.of(
            "caustica_default_surface.slang", "caustica_default_environment.slang",
            "caustica_default_medium.slang", "default_pack.slang");

    private final SlangSession session;
    // Kept so compilePlain can read a module's own extracted text back for SlangSession.compile, which
    // takes source as a string rather than resolving a module by name through the search path.
    private final Path worldDirectory;
    private final Map<String, byte[]> spirvByKey = new HashMap<>();

    private RayPackShaderCompiler(SlangSession session, Path worldDirectory) {
        this.session = session;
        this.worldDirectory = worldDirectory;
    }

    /**
     * Extracts the world-pipeline, Pack API, and bundled-pack sources under {@code cacheDirectory} and
     * opens a session over them. {@code cacheDirectory} should be run-scoped or content-addressed by the
     * caller; this method overwrites whatever is already there.
     */
    public static RayPackShaderCompiler create(Path cacheDirectory) throws IOException {
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        Path worldDirectory = cacheDirectory.resolve("world");
        Path apiDirectory = cacheDirectory.resolve("api");
        Path packDirectory = cacheDirectory.resolve("pack");
        extractAll(WORLD_SHADER_ROOT, WORLD_MODULES, worldDirectory);
        extractAll(PACK_API_ROOT, PACK_API_MODULES, apiDirectory);
        extractAll(BUNDLED_PACK_ROOT, BUNDLED_PACK_MODULES, packDirectory);

        SlangSession session = SlangRuntime.INSTANCE.openSession(
                List.of(worldDirectory, apiDirectory, packDirectory), false, true);
        return new RayPackShaderCompiler(session, worldDirectory);
    }

    /**
     * SPIR-V for one engine entry point specialized with {@code epoch}'s pack type. Compilation failures
     * propagate as {@code SlangCompilationException} with the compiler's own source-located diagnostics;
     * the caller is expected to keep the previously active epoch live rather than swallow them.
     */
    public synchronized byte[] compileSpecialized(RayPackEpoch epoch, String engineModule, String entryPoint) {
        Objects.requireNonNull(epoch, "epoch");
        String key = "pack:" + epoch.contentHash() + '/' + engineModule + '/' + entryPoint;
        return cached(key, () -> session.compileSpecialized(engineModule, entryPoint,
                epoch.manifest().slang().module(), epoch.manifest().slang().type()).spirv(),
                engineModule + ":" + entryPoint + " for " + epoch.id());
    }

    /** SPIR-V for the engine sky miss shader specialized with this epoch's pack. */
    public byte[] compileSkyMiss(RayPackEpoch epoch) {
        return compileSpecialized(epoch, SKY_MISS_MODULE, ENTRY_POINT);
    }

    /** SPIR-V for the engine closest-hit shader specialized with this epoch's pack surface model. */
    public byte[] compileClosestHit(RayPackEpoch epoch) {
        return compileSpecialized(epoch, CLOSEST_HIT_MODULE, ENTRY_POINT);
    }

    /** SPIR-V for the engine indirect raygen specialized with this epoch's pack BSDF/look. */
    public byte[] compileIndirect(RayPackEpoch epoch) {
        return compileSpecialized(epoch, INDIRECT_MODULE, ENTRY_POINT);
    }

    /**
     * SPIR-V for a world-pipeline module's entry point with no pack specialization — the module is
     * compiled exactly as its own source declares it, reading the already-extracted file back so its
     * {@code import}s resolve against the same search-path directory it was extracted into.
     *
     * @param moduleFileName one of {@link #WORLD_MODULES}, e.g. {@code "primary.rgen.slang"}
     */
    public synchronized byte[] compilePlain(String moduleFileName, String entryPoint) {
        Objects.requireNonNull(moduleFileName, "moduleFileName");
        Objects.requireNonNull(entryPoint, "entryPoint");
        String key = "plain:" + moduleFileName + '/' + entryPoint;
        return cached(key, () -> {
            Path file = worldDirectory.resolve(moduleFileName);
            String source;
            try {
                source = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + file + " for runtime compilation", e);
            }
            // Strip only the trailing ".slang", not the first dot: "sky.rmiss.slang" and "sky.slang"
            // must not collapse to the same synthetic module name.
            String withoutExtension = moduleFileName.endsWith(".slang")
                    ? moduleFileName.substring(0, moduleFileName.length() - ".slang".length())
                    : moduleFileName;
            String syntheticModuleName = withoutExtension.replace('.', '_');
            return session.compile(syntheticModuleName, file.toString(), source, entryPoint).spirv();
        }, moduleFileName + ":" + entryPoint);
    }

    private byte[] cached(String key, Supplier<byte[]> compile, String logLabel) {
        byte[] existing = spirvByKey.get(key);
        if (existing != null) {
            return existing;
        }
        long startNanos = System.nanoTime();
        byte[] spirv = compile.get();
        spirvByKey.put(key, spirv);
        CausticaMod.LOGGER.info(String.format(Locale.ROOT,
                "Compiled ray-pack entry point %s in %.1f ms (%d bytes SPIR-V)",
                logLabel, (System.nanoTime() - startNanos) / 1.0e6, spirv.length));
        return spirv;
    }

    @Override
    public void close() {
        session.close();
    }

    private static void extractAll(String resourceRoot, List<String> names, Path destination)
            throws IOException {
        Files.createDirectories(destination);
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            String resource = resourceRoot + name;
            try (InputStream input = RayPackShaderCompiler.class.getResourceAsStream(resource)) {
                if (input == null) {
                    missing.add(resource);
                    continue;
                }
                Files.write(destination.resolve(name), input.readAllBytes());
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Missing bundled Slang source(s): " + String.join(", ", missing));
        }
    }

    /** Wraps checked extraction failures for call sites that cannot usefully recover. */
    public static RayPackShaderCompiler createUnchecked(Path cacheDirectory) {
        try {
            return create(cacheDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare ray-pack shader sources", e);
        }
    }
}
