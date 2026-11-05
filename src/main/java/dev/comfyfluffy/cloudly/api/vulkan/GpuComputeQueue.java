/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;


import dev.comfyfluffy.cloudly.vulkan.ResourceLifetime;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Async work uses the standalone device's serialized compute executor and render-thread publication. */
public final class GpuComputeQueue implements AutoCloseable {
    private final GpuDevice gpu;
    private final ConcurrentLinkedQueue<Runnable> completed = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    private int outstanding;
    public GpuComputeQueue(GpuDevice gpu) { this.gpu = gpu; }
    public int[] sharedQueueFamilyIndices() { return gpu.asyncBufferSharingQueueFamilies(); }
    public GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                                Consumer<? super GpuComputeCompletion> completion) {
        synchronized (completed) {
            if (closed) throw new IllegalStateException("Cloudly compute queue is closed");
            outstanding++;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
        gpu.device().submit(commands -> {
            if (cancelled.get()) throw new CancellationException();
            recorder.accept(commands);
        }, failure -> {
            GpuComputeCompletion result = failure == null ? new GpuComputeCompletion.Succeeded()
                    : failure instanceof CancellationException ? new GpuComputeCompletion.Cancelled()
                    : new GpuComputeCompletion.Failed(failure);
            Runnable publish = () -> {
                try {
                    completion.accept((closed || cancelled.get()) && failure == null ? new GpuComputeCompletion.Cancelled() : result);
                } finally {
                    synchronized (completed) { outstanding--; completed.notifyAll(); }
                }
            };
            synchronized (completed) { completed.add(publish); completed.notifyAll(); }
        });
        } catch (RuntimeException | Error failure) {
            synchronized (completed) { outstanding--; completed.notifyAll(); }
            throw failure;
        }
        return () -> cancelled.set(true);
    }
    public GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                                List<? extends AutoCloseable> dependencies,
                                Consumer<? super GpuComputeCompletion> completion) {
        var owned = List.copyOf(dependencies);
        return submit(recorder, result -> {
            try { completion.accept(result); }
            finally {
                new ResourceLifetime(owned.stream().<Runnable>map(owner -> () -> {
                    try { owner.close(); } catch (Exception failure) { throw new IllegalStateException(failure); }
                }).toArray(Runnable[]::new)).close();
            }
        });
    }
    /** Publish preparation results on the render thread before recording a native frame. */
    public void publishCompleted() {
        Throwable failure = null;
        Runnable next;
        while ((next = completed.poll()) != null) {
            try { next.run(); }
            catch (RuntimeException | Error nextFailure) {
                if (failure == null) failure = nextFailure;
                else if (failure != nextFailure) failure.addSuppressed(nextFailure);
            }
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
    @Override public void close() {
        synchronized (completed) { closed = true; }
        boolean interrupted = false;
        Throwable failure = null;
        while (true) {
            try { publishCompleted(); }
            catch (RuntimeException | Error nextFailure) {
                if (failure == null) failure = nextFailure;
                else if (failure != nextFailure) failure.addSuppressed(nextFailure);
            }
            synchronized (completed) {
                if (outstanding == 0) break;
                try { completed.wait(20L); }
                catch (InterruptedException interruptedWait) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
}
