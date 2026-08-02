package dev.comfyfluffy.caustica.slang;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

public final class SlangSession implements AutoCloseable {
    private final SlangLibrary library;
    private final Runnable onClose;
    private MemorySegment handle;

    SlangSession(SlangLibrary library, MemorySegment handle, Runnable onClose) {
        this.library = Objects.requireNonNull(library, "library");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
    }

    public synchronized SlangCompileResult compile(String moduleName, String sourcePath,
                                                   String source, String entryPoint) {
        if (handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Slang session is closed");
        }
        return library.compile(handle,
                Objects.requireNonNull(moduleName, "moduleName"),
                Objects.requireNonNull(sourcePath, "sourcePath"),
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(entryPoint, "entryPoint"));
    }

    public synchronized SlangCompileResult compileSpecialized(String engineModule, String entryPoint,
                                                               String packModule, String packType) {
        if (handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Slang session is closed");
        }
        return library.compileSpecialized(handle,
                Objects.requireNonNull(engineModule, "engineModule"),
                Objects.requireNonNull(entryPoint, "entryPoint"),
                Objects.requireNonNull(packModule, "packModule"),
                Objects.requireNonNull(packType, "packType"));
    }

    synchronized boolean isClosed() {
        return handle.equals(MemorySegment.NULL);
    }

    @Override
    public void close() {
        boolean closed = false;
        synchronized (this) {
            if (!handle.equals(MemorySegment.NULL)) {
                try {
                    library.destroySession(handle);
                } finally {
                    handle = MemorySegment.NULL;
                    closed = true;
                }
            }
        }
        if (closed) {
            onClose.run();
        }
    }
}
