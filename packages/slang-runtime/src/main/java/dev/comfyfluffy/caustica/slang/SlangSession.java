package dev.comfyfluffy.caustica.slang;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.function.Consumer;

public final class SlangSession implements AutoCloseable {
    private final SlangLibrary library;
    private final Consumer<SlangSession> onClose;
    private MemorySegment handle;
    private boolean retainedUntilProcessExit;

    SlangSession(SlangLibrary library, MemorySegment handle, Consumer<SlangSession> onClose) {
        this.library = Objects.requireNonNull(library, "library");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
    }

    public synchronized SlangCompileResult compile(String moduleName, String sourcePath,
                                                   String source, String entryPoint) {
        if (isClosed()) {
            throw new IllegalStateException("Slang session is closed");
        }
        return library.compile(handle,
                Objects.requireNonNull(moduleName, "moduleName"),
                Objects.requireNonNull(sourcePath, "sourcePath"),
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(entryPoint, "entryPoint"));
    }

    public synchronized SlangCompileResult compileSpecialized(String engineModule, String entryPoint,
                                                               String implementationModule,
                                                               String implementationType) {
        if (isClosed()) {
            throw new IllegalStateException("Slang session is closed");
        }
        return library.compileSpecialized(handle,
                Objects.requireNonNull(engineModule, "engineModule"),
                Objects.requireNonNull(entryPoint, "entryPoint"),
                Objects.requireNonNull(implementationModule, "implementationModule"),
                Objects.requireNonNull(implementationType, "implementationType"));
    }

    private boolean isClosed() {
        return retainedUntilProcessExit || handle.equals(MemorySegment.NULL);
    }

    /**
     * Make the session unusable while leaving its native compiler graph for the operating system to reclaim.
     * Specialized world-pipeline sessions use this lifetime because their native compiler graph remains
     * referenced for the duration of the process.
     */
    public synchronized void retainUntilProcessExit() {
        if (!handle.equals(MemorySegment.NULL)) {
            retainedUntilProcessExit = true;
        }
    }

    @Override
    public void close() {
        MemorySegment closingHandle;
        synchronized (this) {
            if (isClosed()) {
                return;
            }
            // No new compiler call may use the handle once native cleanup begins.
            closingHandle = handle;
            handle = MemorySegment.NULL;
        }
        try {
            library.destroySession(closingHandle);
        } finally {
            // The runtime callback takes its own monitor, so invoke it outside the session monitor.
            onClose.accept(this);
        }
    }
}
