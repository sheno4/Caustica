package dev.comfyfluffy.caustica.api.gpu;

/** A command-buffer debug label scope supplied by the renderer's GPU device. */
@FunctionalInterface
public interface GpuDebugScope extends AutoCloseable {
    @Override
    void close();
}
