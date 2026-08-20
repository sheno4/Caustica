package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.rt.pipeline.RtShaderCode;
import dev.comfyfluffy.caustica.rt.shader.Composition;
import dev.comfyfluffy.caustica.rt.shader.WorldShaderCompiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Compiles and caches immutable world programs independently of any world or resource-pack epoch. */
final class RtProgramManager {
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Caustica shader build-" + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final Map<Key, Program> cache = new ConcurrentHashMap<>();
    private Path cacheRoot;
    private Key requested;
    private Program active;
    private Program candidate;
    private Pending pending;
    private Failed failed;

    RtProgramManager() {
    }

    public synchronized void configureCacheRoot(Path root) {
        cacheRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** Request a composition without disturbing the program currently used for rendering. */
    public synchronized void request(CausticaRegistry.Selection selection, boolean reordered) {
        Key key = new Key(selection, reordered);
        if (key.equals(requested)) {
            pollPending();
            return;
        }
        requested = key;
        failed = null;
        if (candidate != null && !candidate.key().equals(key)) {
            candidate = null;
        }
        abandonPending();
        if (active != null && active.key().equals(key)) {
            return;
        }
        Program cached = cache.get(key);
        if (cached != null) {
            candidate = cached;
            CausticaMod.LOGGER.info("World program candidate reused from the process cache");
            return;
        }
        Path root = Objects.requireNonNull(cacheRoot, "shader cache root was not configured by the host");
        AtomicBoolean abandoned = new AtomicBoolean();
        pending = new Pending(key, abandoned, CompletableFuture.supplyAsync(
                () -> build(root, key, abandoned), buildExecutor));
        CausticaMod.LOGGER.info("Preparing world program candidate off thread");
    }

    /** The requested program once compilation has completed, or {@code null} while it remains pending. */
    public synchronized Program candidate() {
        pollPending();
        if (active != null && active.key().equals(requested)) {
            return active;
        }
        return candidate != null && candidate.key().equals(requested) ? candidate : null;
    }

    public synchronized Program active() {
        return active;
    }

    public synchronized Throwable requestedFailure() {
        pollPending();
        return failed != null && failed.key().equals(requested) ? failed.cause() : null;
    }

    /** Publish a successfully installed candidate at a render-thread boundary. */
    public synchronized void activate(Program installed) {
        Objects.requireNonNull(installed, "installed");
        if (!installed.key().equals(requested)) {
            throw new IllegalArgumentException("Cannot activate a stale world program candidate");
        }
        active = installed;
        candidate = null;
        failed = null;
        CausticaMod.LOGGER.info("World program epoch active: sky={}, SER={}",
                installed.key().selection().binding(Slots.SKY).feature().id(),
                installed.key().reordered() ? "EXT" : "none");
    }

    /** Stop process-scoped compilation work during client shutdown. */
    synchronized void shutdown() {
        abandonPending();
        buildExecutor.shutdownNow();
        active = null;
        candidate = null;
        failed = null;
        requested = null;
        cache.clear();
    }

    private void pollPending() {
        if (pending == null || !pending.future().isDone()) {
            return;
        }
        Pending completed = pending;
        pending = null;
        try {
            Program built = completed.future().join();
            if (built != null && completed.key().equals(requested)) {
                candidate = built;
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (completed.key().equals(requested)) {
                failed = new Failed(completed.key(), cause);
                CausticaMod.LOGGER.error("World program candidate failed; retaining the active program", cause);
            }
        }
    }

    private Program build(Path root, Key key, AtomicBoolean abandoned) {
        Program cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (abandoned.get()) {
            return null;
        }
        WorldShaderCompiler compiler = null;
        try {
            compiler = WorldShaderCompiler.createIsolated(root, key.selection());
            if (abandoned.get()) {
                return null;
            }
            Shaders shaders = compile(compiler, key.reordered());
            Program built = new Program(key, shaders, compiler.passResourceBindings(),
                    compiler.rejectedSurfaces());
            Program existing = cache.putIfAbsent(key, built);
            return existing != null ? existing : built;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare world shader sources", e);
        } finally {
            if (compiler != null) {
                compiler.close();
            }
        }
    }

    private static Shaders compile(WorldShaderCompiler compiler, boolean reordered) {
        Composition composition = compiler.composition();
        var sky = composition.selection().binding(Slots.SKY);
        int surfaceCount = composition.selection().surfaces().size();
        RtShaderCode primary = RtShaderCode.of("primary", compiler.compilePrimary());
        RtShaderCode indirect = RtShaderCode.of(
                "indirect(" + surfaceCount + " surfaces" + (reordered ? ", EXT_SER)" : ")"),
                compiler.compileIndirect(reordered));
        RtShaderCode skyMiss = RtShaderCode.of(
                "sky_miss(" + sky.feature().id() + ")", compiler.compileSkyMiss());
        RtShaderCode guideMiss = RtShaderCode.of("guide_miss",
                compiler.compilePlain("guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT));
        RtShaderCode closestHit = RtShaderCode.of(
                "closest_hit(" + surfaceCount + " surfaces)", compiler.compileClosestHit());
        RtShaderCode radianceAnyHit = RtShaderCode.of("radiance_any_hit",
                compiler.compileRadianceAnyHit());
        RtShaderCode shadowAnyHit = RtShaderCode.of("shadow_any_hit",
                compiler.compileShadowAnyHit());
        CausticaMod.LOGGER.info("World program candidate compiled: sky={} ({}), surfaces={}, SER={}",
                sky.feature().id(), sky.binding().type(),
                composition.selection().surfaces().stream()
                        .map(implementation -> implementation.id().toString()).toList(),
                reordered ? "EXT" : "none");
        return new Shaders(primary, indirect, skyMiss, guideMiss, closestHit,
                radianceAnyHit, shadowAnyHit);
    }

    private void abandonPending() {
        if (pending != null) {
            pending.abandoned().set(true);
            pending = null;
        }
    }

    public record Key(CausticaRegistry.Selection selection, boolean reordered) {
        public Key {
            Objects.requireNonNull(selection, "selection");
        }
    }

    public record Shaders(RtShaderCode primary, RtShaderCode indirect, RtShaderCode skyMiss,
                          RtShaderCode guideMiss, RtShaderCode closestHit,
                          RtShaderCode radianceAnyHit, RtShaderCode shadowAnyHit) {
    }

    public record Program(Key key, Shaders shaders,
                          Map<String, WorldShaderCompiler.PassResourceBinding> passResourceBindings,
                          java.util.Set<Integer> rejectedSurfaces) {
        public Program {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(shaders, "shaders");
            passResourceBindings = Map.copyOf(passResourceBindings);
            rejectedSurfaces = java.util.Set.copyOf(rejectedSurfaces);
        }
    }

    private record Pending(Key key, AtomicBoolean abandoned, CompletableFuture<Program> future) {
    }

    private record Failed(Key key, Throwable cause) {
    }
}
