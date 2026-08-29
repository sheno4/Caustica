package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.api.program.ProgramFailure;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramKey;
import dev.comfyfluffy.caustica.rt.pipeline.RtBindings;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtShaderCode;
import dev.comfyfluffy.caustica.rt.shader.WorldShaderCompiler;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static dev.comfyfluffy.caustica.rt.GpuContext.check;

/** Compiles engine program compositions and publishes complete descriptor-heap RT programs. */
public final class RtProgramBackend implements ProgramBackend, AutoCloseable {
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private final GpuContext context;
    private final Path cacheRoot;
    private final ExecutorService compiler = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Caustica program compiler-" + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Candidate> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
    private Candidate active;
    private boolean closed;

    public RtProgramBackend(GpuContext context, Path cacheRoot) {
        this.context = Objects.requireNonNull(context, "context");
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
            if (next.disposed) throw new IllegalStateException("program candidate is disposed");
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
                previous.destroy();
                previousRetired.run();
            });
        }
    }

    @Override
    public void discard(CompiledProgram program) {
        Candidate candidate = requireCandidate(program);
        synchronized (this) {
            if (candidate == active) throw new IllegalStateException("cannot discard the active program");
            if (candidate.state == CandidateState.RETIRING) {
                throw new IllegalStateException("cannot discard a program pending GPU retirement");
            }
        }
        candidate.destroy();
    }

    @Override
    public void drainPublishedUses() {
        context.gpuExecutor().drainAndWaitIdle();
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
        List<Candidate> unpublished;
        synchronized (this) {
            unpublished = candidates.stream()
                    .filter(candidate -> candidate.state == CandidateState.CANDIDATE)
                    .toList();
        }
        unpublished.forEach(Candidate::destroy);
        if (previous != null) context.retireAfterUse(previous::destroy);
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
            shaderCompiler = WorldShaderCompiler.createIsolated(cacheRoot, composition);
            List<Long> data = shaderCompiler.composition().implementationData();
            table = ImplementationTable.create(context, data);
            boolean reordered = context.backend().capabilities().shaderExecutionReordering();
            RtShaderCode primary = RtShaderCode.of("primary", shaderCompiler.compilePrimary());
            RtShaderCode indirect = RtShaderCode.of("indirect", shaderCompiler.compileIndirect(reordered));
            RtShaderCode environment = RtShaderCode.of("environment", shaderCompiler.compileSkyMiss());
            RtShaderCode guide = RtShaderCode.of("guide", shaderCompiler.compilePlain(
                    "guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT));
            RtShaderCode closest = RtShaderCode.of("closest-hit", shaderCompiler.compileClosestHit());
            RtShaderCode radiance = RtShaderCode.of("radiance-any-hit", shaderCompiler.compileRadianceAnyHit());
            RtShaderCode shadow = RtShaderCode.of("shadow-any-hit", shaderCompiler.compileShadowAnyHit());
            pipeline = RtPipeline.create(context, new RtShaderCode[]{primary, indirect},
                    new RtShaderCode[]{environment, guide}, closest, radiance, shadow);
            Candidate candidate = new Candidate(shaderCompiler, table, pipeline);
            synchronized (this) {
                candidates.add(candidate);
            }
            return candidate;
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
        long compositionDataAddress();

        /** Writes this program's implementation table address into an 88-byte world binding root. */
        default void writeCompositionDataAddress(ByteBuffer roots) {
            Objects.requireNonNull(roots, "roots");
            if (roots.remaining() != RtBindings.WORLD_PUSH_CONSTANT_SIZE) {
                throw new IllegalArgumentException("world binding root has the wrong size");
            }
            roots.duplicate().order(ByteOrder.nativeOrder()).putLong(
                    roots.position() + RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET,
                    compositionDataAddress());
        }
    }

    private final class Candidate implements Published {
        private final RtProgramBackend owner = RtProgramBackend.this;
        private final WorldShaderCompiler compiler;
        private final ImplementationTable table;
        private final RtPipeline pipeline;
        private CandidateState state = CandidateState.CANDIDATE;
        private boolean disposed;

        private Candidate(WorldShaderCompiler compiler, ImplementationTable table, RtPipeline pipeline) {
            this.compiler = compiler;
            this.table = table;
            this.pipeline = pipeline;
        }

        @Override public int implementationIndex(ProgramKey key) { return compiler.implementationIndex(key); }
        @Override public RtPipeline pipeline() { return pipeline; }
        @Override public long compositionDataAddress() { return table.address; }

        void destroy() {
            synchronized (RtProgramBackend.this) {
                if (disposed) return;
                state = CandidateState.DISPOSED;
                candidates.remove(this);
                pipeline.destroy();
                table.destroy();
                compiler.close();
                disposed = true;
            }
        }
    }

    private enum CandidateState { CANDIDATE, ACTIVE, RETIRING, DISPOSED }

    private static final class ImplementationTable {
        final GpuContext context;
        final long buffer;
        final long allocation;
        final long address;

        private ImplementationTable(GpuContext context, long buffer, long allocation, long address) {
            this.context = context;
            this.buffer = buffer;
            this.allocation = allocation;
            this.address = address;
        }

        static ImplementationTable create(GpuContext context, List<Long> words) {
            long size = Math.max(Long.BYTES, Math.multiplyExact((long) words.size(), Long.BYTES));
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                        .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                                | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                LongBuffer outBuffer = stack.mallocLong(1);
                PointerBuffer outAllocation = stack.mallocPointer(1);
                VmaAllocationInfo allocationInfoOut = VmaAllocationInfo.calloc(stack);
                check(Vma.vmaCreateBuffer(context.vmaAllocator(), bufferInfo, allocationInfo,
                        outBuffer, outAllocation, allocationInfoOut), "vmaCreateBuffer(program data)");
                long buffer = outBuffer.get(0);
                long allocation = outAllocation.get(0);
                long address = VK12.vkGetBufferDeviceAddress(context.vk(),
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
                if (address == 0L || allocationInfoOut.pMappedData() == 0L) {
                    Vma.vmaDestroyBuffer(context.vmaAllocator(), buffer, allocation);
                    throw new IllegalStateException("program implementation table is not addressable and mapped");
                }
                ByteBuffer mapped = MemoryUtil.memByteBuffer(allocationInfoOut.pMappedData(), Math.toIntExact(size))
                        .order(ByteOrder.nativeOrder());
                for (Long word : words) mapped.putLong(word);
                if (words.isEmpty()) mapped.putLong(0L);
                Vma.vmaFlushAllocation(context.vmaAllocator(), allocation, 0, VK10.VK_WHOLE_SIZE);
                return new ImplementationTable(context, buffer, allocation, address);
            }
        }

        void destroy() { Vma.vmaDestroyBuffer(context.vmaAllocator(), buffer, allocation); }
    }
}
