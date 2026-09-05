package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;

import dev.comfyfluffy.caustica.api.program.ProgramFailure;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtShaderCode;
import dev.comfyfluffy.caustica.renderer.raytracing.shader.WorldShaderCompiler;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Compiles engine program compositions and publishes complete descriptor-heap RT programs. */
public final class RtProgramBackend implements ProgramBackend, AutoCloseable {
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private final VulkanDeviceContext context;
    private final SlangRuntime slangRuntime;
    private final Path cacheRoot;
    private final ExecutorService compiler = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Caustica program compiler-" + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private Candidate active;
    private boolean closed;

    public RtProgramBackend(VulkanDeviceContext context, SlangRuntime slangRuntime, Path cacheRoot) {
        this.context = Objects.requireNonNull(context, "context");
        this.slangRuntime = Objects.requireNonNull(slangRuntime, "slangRuntime");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
    }

    @Override
    public void compile(ProgramComposition composition, Consumer<? super Compilation> completion) {
        Objects.requireNonNull(composition, "composition");
        Objects.requireNonNull(completion, "completion");
        synchronized (this) {
            if (closed) throw new IllegalStateException("program backend is closed");
        }
        compiler.execute(() -> {
            Compilation result;
            try {
                result = new Compilation.Succeeded(build(composition));
            } catch (Throwable failure) {
                result = new Compilation.Failed(new ProgramFailure(
                        "World program compilation failed", diagnostics(failure)));
            }
            completion.accept(result);
        });
    }

    @Override
    public void publish(CompiledProgram program, Runnable previousRetired) {
        Candidate next = requireCandidate(program);
        Objects.requireNonNull(previousRetired, "previousRetired");
        Candidate previous;
        synchronized (this) {
            if (closed) throw new IllegalStateException("program backend is closed");
            if (next.state != CandidateState.CANDIDATE) {
                throw new IllegalStateException("program candidate is already published or retiring");
            }
            previous = active;
            active = next;
            next.state = CandidateState.ACTIVE;
            if (previous != null) previous.state = CandidateState.RETIRING;
        }
        if (previous == null) {
            previousRetired.run();
        } else {
            context.retireAfterUse(() -> {
                previous.close();
                previousRetired.run();
            });
        }
    }

    @Override
    public void drainPublishedUses() {
        context.drainAndWaitIdle();
    }

    /** Currently published renderer program, or {@code null} before first publication. */
    public synchronized Published active() {
        return active;
    }

    @Override
    public void close() {
        Candidate previous;
        synchronized (this) {
            if (closed) return;
            closed = true;
            previous = active;
            active = null;
            if (previous != null) previous.state = CandidateState.RETIRING;
        }
        compiler.shutdown();
        awaitTerminationUninterruptibly(compiler);
        if (previous != null) context.retireAfterUse(previous::close);
    }

    static void awaitTerminationUninterruptibly(ExecutorService executor) {
        boolean interrupted = false;
        while (true) {
            try {
                if (executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private Candidate build(ProgramComposition composition) throws IOException {
        WorldShaderCompiler shaderCompiler = null;
        ImplementationTable table = null;
        RtPipeline pipeline = null;
        try {
            shaderCompiler = WorldShaderCompiler.createIsolated(slangRuntime, cacheRoot, composition);
            List<Long> data = shaderCompiler.implementationData();
            table = ImplementationTable.create(context, data);
            boolean reordered = context.backend().capabilities().shaderExecutionReordering();
            RtShaderCode build = RtShaderCode.of("build-stable-planes", shaderCompiler.compileBuildStablePlanes());
            RtShaderCode fill = RtShaderCode.of("fill-stable-planes", shaderCompiler.compileFillStablePlanes(reordered));
            RtShaderCode environment = RtShaderCode.of("environment", shaderCompiler.compileSkyMiss());
            RtShaderCode guide = RtShaderCode.of("guide", shaderCompiler.compilePlain(
                    "guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT));
            RtShaderCode closest = RtShaderCode.of("closest-hit", shaderCompiler.compileClosestHit());
            RtShaderCode radiance = RtShaderCode.of("radiance-any-hit", shaderCompiler.compileRadianceAnyHit());
            RtShaderCode shadow = RtShaderCode.of("shadow-any-hit", shaderCompiler.compileShadowAnyHit());
            pipeline = RtPipeline.create(context, new RtShaderCode[]{build, fill},
                    new RtShaderCode[]{environment, guide}, closest, radiance, shadow);
            return new Candidate(shaderCompiler, table, pipeline);
        } catch (IOException | RuntimeException | Error failure) {
            if (pipeline != null) pipeline.destroy();
            if (table != null) table.destroy();
            if (shaderCompiler != null) shaderCompiler.close();
            throw failure;
        }
    }

    private Candidate requireCandidate(CompiledProgram program) {
        if (!(program instanceof Candidate candidate) || candidate.owner != this) {
            throw new IllegalArgumentException("program belongs to another backend");
        }
        return candidate;
    }

    private static String diagnostics(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null ? "" : ": " + message);
    }

    /** Renderer-facing immutable state of one published program. */
    public interface Published extends CompiledProgram {
        RtPipeline pipeline();
        VulkanDeviceAddress compositionDataAddress();

        /** Writes this program's implementation table address into a complete world binding root. */
        default void writeCompositionDataAddress(ByteBuffer roots) {
            Objects.requireNonNull(roots, "roots");
            if (roots.remaining() != RtBindings.WORLD_PUSH_CONSTANT_SIZE) {
                throw new IllegalArgumentException("world binding root has the wrong size");
            }
            roots.duplicate().order(ByteOrder.nativeOrder()).putLong(
                    roots.position() + RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET,
                    compositionDataAddress().value());
        }
    }

    private final class Candidate implements Published {
        private final RtProgramBackend owner = RtProgramBackend.this;
        private final WorldShaderCompiler compiler;
        private final ImplementationTable table;
        private final RtPipeline pipeline;
        private CandidateState state = CandidateState.CANDIDATE;

        private Candidate(WorldShaderCompiler compiler, ImplementationTable table, RtPipeline pipeline) {
            this.compiler = compiler;
            this.table = table;
            this.pipeline = pipeline;
        }

        @Override public RtPipeline pipeline() { return pipeline; }
        @Override public VulkanDeviceAddress compositionDataAddress() { return table.address; }

        @Override public void close() {
            synchronized (RtProgramBackend.this) {
                if (state == CandidateState.DISPOSED) return;
                if (state == CandidateState.ACTIVE) {
                    throw new IllegalStateException("cannot close the active program");
                }
                state = CandidateState.DISPOSED;
                pipeline.destroy();
                table.destroy();
                compiler.close();
            }
        }
    }

    private enum CandidateState { CANDIDATE, ACTIVE, RETIRING, DISPOSED }

    private static final class ImplementationTable {
        final VmaMappedBuffer storage;
        final VulkanDeviceAddress address;

        private ImplementationTable(VmaMappedBuffer storage) {
            this.storage = storage;
            this.address = storage.deviceRange().address();
        }

        static ImplementationTable create(VulkanDeviceContext context, List<Long> words) {
            long size = Math.max(Long.BYTES, Math.multiplyExact((long) words.size(), Long.BYTES));
            VmaMappedBuffer storage = VmaMappedBuffer.create(
                    context, size, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "program data");
            try {
                ByteBuffer mapped = storage.mapped().order(ByteOrder.nativeOrder());
                for (Long word : words) mapped.putLong(word);
                if (words.isEmpty()) mapped.putLong(0L);
                storage.flush(0L, size);
                return new ImplementationTable(storage);
            } catch (RuntimeException | Error failure) {
                storage.close();
                throw failure;
            }
        }

        void destroy() { storage.close(); }
    }
}
