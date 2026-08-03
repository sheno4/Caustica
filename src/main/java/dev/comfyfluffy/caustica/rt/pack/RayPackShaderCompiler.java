package dev.comfyfluffy.caustica.rt.pack;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.slang.SlangCompileResult;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles engine ray-tracing entry points specialized with the active epoch's pack type
 * (docs/RAY_PACK_ARCHITECTURE.md section 8). This is the single place Slang is invoked for appearance
 * composition — pipeline creation asks for SPIR-V and never talks to the compiler itself.
 *
 * <p>Both the engine modules and the bundled pack's modules ship as classpath resources, but Slang
 * resolves {@code import} through filesystem search paths, so the required source trees are extracted
 * once into a cache directory and the session is pointed at that. An externally installed pack would
 * instead contribute its own already-on-disk source root.
 *
 * <p>Results are cached in memory per (epoch content hash, entry point). The cache key deliberately does
 * NOT yet include the compiler version, engine module hashes, or a capability plan, so it is only valid
 * within a single run — it is not the persistent on-disk cache described in section 8.1.
 */
public final class RayPackShaderCompiler implements AutoCloseable {
    /** Engine-owned entry point: the sky miss shader, generic over the pack's IRayPack implementation. */
    public static final String SKY_MISS_MODULE = "pack_sky_miss";
    public static final String SKY_MISS_ENTRY_POINT = "main";

    private static final String ENGINE_SHADER_ROOT = "/caustica/shadersrc/pipelines/world/";
    private static final String PACK_API_ROOT = "/caustica/raypacks/api/0.1/";
    private static final String BUNDLED_PACK_ROOT = "/caustica/raypacks/default/shaders/";

    // Engine world modules the specialized entry points import, transitively. Listed explicitly rather
    // than by scanning the jar so an unexpected addition is a visible change here, not a silent one.
    private static final List<String> ENGINE_MODULES = List.of(
            "world_common.slang", "world_core.slang", "bindings.slang", "math.slang",
            "medium.slang", "trace.slang", "sky.slang", "pack_sky_miss.slang");
    private static final List<String> PACK_API_MODULES = List.of("caustica_ray_pack_api.slang");
    private static final List<String> BUNDLED_PACK_MODULES = List.of(
            "caustica_default_surface.slang", "caustica_default_environment.slang",
            "caustica_default_medium.slang", "default_pack.slang");

    private final SlangSession session;
    private final Map<String, byte[]> spirvByKey = new HashMap<>();

    private RayPackShaderCompiler(SlangSession session) {
        this.session = session;
    }

    /**
     * Extracts the engine and bundled-pack sources under {@code cacheDirectory} and opens a session over
     * them. {@code cacheDirectory} should be run-scoped or content-addressed by the caller; this method
     * overwrites whatever is already there.
     */
    public static RayPackShaderCompiler create(Path cacheDirectory) throws IOException {
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        Path engineDirectory = cacheDirectory.resolve("engine");
        Path apiDirectory = cacheDirectory.resolve("api");
        Path packDirectory = cacheDirectory.resolve("pack");
        extractAll(ENGINE_SHADER_ROOT, ENGINE_MODULES, engineDirectory);
        extractAll(PACK_API_ROOT, PACK_API_MODULES, apiDirectory);
        extractAll(BUNDLED_PACK_ROOT, BUNDLED_PACK_MODULES, packDirectory);

        SlangSession session = SlangRuntime.INSTANCE.openSession(
                List.of(engineDirectory, apiDirectory, packDirectory), false, true);
        return new RayPackShaderCompiler(session);
    }

    /**
     * SPIR-V for one engine entry point specialized with {@code epoch}'s pack type. Compilation failures
     * propagate as {@code SlangCompilationException} with the compiler's own source-located diagnostics;
     * the caller is expected to keep the previously active epoch live rather than swallow them.
     */
    public synchronized byte[] compile(RayPackEpoch epoch, String engineModule, String entryPoint) {
        Objects.requireNonNull(epoch, "epoch");
        String key = epoch.contentHash() + '/' + engineModule + '/' + entryPoint;
        byte[] cached = spirvByKey.get(key);
        if (cached != null) {
            return cached;
        }
        long startNanos = System.nanoTime();
        SlangCompileResult result = session.compileSpecialized(engineModule, entryPoint,
                epoch.manifest().slang().module(), epoch.manifest().slang().type());
        byte[] spirv = result.spirv();
        spirvByKey.put(key, spirv);
        CausticaMod.LOGGER.info(String.format(Locale.ROOT,
                "Compiled ray-pack entry point %s:%s for %s in %.1f ms (%d bytes SPIR-V)",
                engineModule, entryPoint, epoch.id(), (System.nanoTime() - startNanos) / 1.0e6,
                spirv.length));
        return spirv;
    }

    /** SPIR-V for the engine sky miss shader specialized with this epoch's pack. */
    public byte[] compileSkyMiss(RayPackEpoch epoch) {
        return compile(epoch, SKY_MISS_MODULE, SKY_MISS_ENTRY_POINT);
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
