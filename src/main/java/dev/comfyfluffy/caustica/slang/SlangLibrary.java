package dev.comfyfluffy.caustica.slang;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class SlangLibrary {
    static final int ABI_VERSION = 2;
    static final int SESSION_DEBUG_INFO = 1;
    static final int SESSION_WARNINGS_AS_ERRORS = 2;

    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle abiVersion;
    private final MethodHandle compilerVersion;
    private final MethodHandle runtimeCreate;
    private final MethodHandle runtimeDestroy;
    private final MethodHandle sessionCreate;
    private final MethodHandle sessionDestroy;
    private final MethodHandle compileEntryPoint;
    private final MethodHandle compileSpecializedEntryPoint;
    private final MethodHandle blobData;
    private final MethodHandle blobSize;
    private final MethodHandle blobDestroy;

    private SlangLibrary(SymbolLookup lookup) {
        abiVersion = handle(lookup, "caustica_slang_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        compilerVersion = handle(lookup, "caustica_slang_compiler_version", FunctionDescriptor.of(ValueLayout.ADDRESS));
        runtimeCreate = handle(lookup, "caustica_slang_runtime_create",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        runtimeDestroy = handle(lookup, "caustica_slang_runtime_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        sessionCreate = handle(lookup, "caustica_slang_session_create",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        sessionDestroy = handle(lookup, "caustica_slang_session_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        compileEntryPoint = handle(lookup, "caustica_slang_compile_entry_point",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        compileSpecializedEntryPoint = handle(lookup, "caustica_slang_compile_specialized_entry_point",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        blobData = handle(lookup, "caustica_slang_blob_data",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        blobSize = handle(lookup, "caustica_slang_blob_size",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        blobDestroy = handle(lookup, "caustica_slang_blob_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    static SlangLibrary load(Path directory, SlangPlatform platform) {
        Path compiler = directory.resolve(platform.compilerName());
        Path core = directory.resolve(platform.coreName());
        Path shim = directory.resolve(platform.shimName());
        for (Path required : List.of(compiler, core, shim)) {
            if (!Files.isRegularFile(required)) {
                throw new IllegalStateException("Missing Slang runtime library " + required);
            }
        }
        System.load(compiler.toAbsolutePath().toString());
        System.load(core.toAbsolutePath().toString());
        SlangLibrary library = new SlangLibrary(SymbolLookup.libraryLookup(shim, Arena.global()));
        if (library.abiVersion() != ABI_VERSION) {
            throw new IllegalStateException("Slang shim ABI mismatch: expected " + ABI_VERSION
                    + ", got " + library.abiVersion());
        }
        return library;
    }

    int abiVersion() {
        try {
            return (int) abiVersion.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("caustica_slang_abi_version failed", t);
        }
    }

    String compilerVersion() {
        try {
            MemorySegment value = (MemorySegment) compilerVersion.invokeExact();
            return value.equals(MemorySegment.NULL) ? "unknown" : value.reinterpret(4096).getString(0);
        } catch (Throwable t) {
            throw new RuntimeException("caustica_slang_compiler_version failed", t);
        }
    }

    MemorySegment createRuntime() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment runtimeOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment diagnosticsOut = arena.allocate(ValueLayout.ADDRESS);
            int result = (int) runtimeCreate.invokeExact(runtimeOut, diagnosticsOut);
            MemorySegment diagnostics = diagnosticsOut.get(ValueLayout.ADDRESS, 0);
            String text = takeString(diagnostics);
            if (result < 0) {
                throw new SlangCompilationException("Creating Slang runtime", result, text);
            }
            MemorySegment runtime = runtimeOut.get(ValueLayout.ADDRESS, 0);
            if (runtime.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Slang returned a null runtime after successful creation");
            }
            return runtime;
        } catch (SlangCompilationException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("caustica_slang_runtime_create failed", t);
        }
    }

    void destroyRuntime(MemorySegment runtime) {
        if (runtime.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            runtimeDestroy.invokeExact(runtime);
        } catch (Throwable t) {
            throw new RuntimeException("caustica_slang_runtime_destroy failed", t);
        }
    }

    MemorySegment createSession(MemorySegment runtime, List<Path> searchPaths, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathArray = searchPaths.isEmpty()
                    ? MemorySegment.NULL : arena.allocate(ValueLayout.ADDRESS, searchPaths.size());
            for (int i = 0; i < searchPaths.size(); i++) {
                pathArray.setAtIndex(ValueLayout.ADDRESS, i,
                        arena.allocateFrom(searchPaths.get(i).toAbsolutePath().normalize().toString()));
            }
            MemorySegment sessionOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment diagnosticsOut = arena.allocate(ValueLayout.ADDRESS);
            int result = (int) sessionCreate.invokeExact(runtime, pathArray, (long) searchPaths.size(), flags,
                    sessionOut, diagnosticsOut);
            String diagnostics = takeString(diagnosticsOut.get(ValueLayout.ADDRESS, 0));
            if (result < 0) {
                throw new SlangCompilationException("Creating Slang session", result, diagnostics);
            }
            MemorySegment session = sessionOut.get(ValueLayout.ADDRESS, 0);
            if (session.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Slang returned a null session after successful creation");
            }
            return session;
        } catch (SlangCompilationException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("caustica_slang_session_create failed", t);
        }
    }

    void destroySession(MemorySegment session) {
        if (session.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            sessionDestroy.invokeExact(session);
        } catch (Throwable t) {
            throw new RuntimeException("caustica_slang_session_destroy failed", t);
        }
    }

    SlangCompileResult compile(MemorySegment session, String moduleName, String sourcePath,
                               String source, String entryPoint) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment spirvOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment reflectionOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment diagnosticsOut = arena.allocate(ValueLayout.ADDRESS);
            int result = (int) compileEntryPoint.invokeExact(session,
                    arena.allocateFrom(moduleName), arena.allocateFrom(sourcePath), arena.allocateFrom(source),
                    arena.allocateFrom(entryPoint), spirvOut, reflectionOut, diagnosticsOut);
            return collectCompileResult(result, "Compiling " + sourcePath + ":" + entryPoint,
                    spirvOut, reflectionOut, diagnosticsOut);
        } catch (SlangCompilationException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("caustica_slang_compile_entry_point failed", t);
        }
    }

    SlangCompileResult compileSpecialized(MemorySegment session, String engineModule, String entryPoint,
                                          String packModule, String packType) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment spirvOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment reflectionOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment diagnosticsOut = arena.allocate(ValueLayout.ADDRESS);
            int result = (int) compileSpecializedEntryPoint.invokeExact(session,
                    arena.allocateFrom(engineModule), arena.allocateFrom(entryPoint),
                    arena.allocateFrom(packModule), arena.allocateFrom(packType),
                    spirvOut, reflectionOut, diagnosticsOut);
            return collectCompileResult(result,
                    "Compiling " + engineModule + ":" + entryPoint + " with " + packModule + "::" + packType,
                    spirvOut, reflectionOut, diagnosticsOut);
        } catch (SlangCompilationException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("caustica_slang_compile_specialized_entry_point failed", t);
        }
    }

    private SlangCompileResult collectCompileResult(int result, String operation,
                                                     MemorySegment spirvOut,
                                                     MemorySegment reflectionOut,
                                                     MemorySegment diagnosticsOut) {
        byte[] spirv = takeBytes(spirvOut.get(ValueLayout.ADDRESS, 0));
        String reflection = takeString(reflectionOut.get(ValueLayout.ADDRESS, 0));
        String diagnostics = takeString(diagnosticsOut.get(ValueLayout.ADDRESS, 0));
        if (result < 0) {
            throw new SlangCompilationException(operation, result, diagnostics);
        }
        if (spirv.length == 0 || (spirv.length & 3) != 0) {
            throw new IllegalStateException("Slang returned invalid SPIR-V byte count " + spirv.length);
        }
        return new SlangCompileResult(spirv, reflection, diagnostics);
    }

    private byte[] takeBytes(MemorySegment blob) {
        if (blob.equals(MemorySegment.NULL)) {
            return new byte[0];
        }
        try {
            long size = (long) blobSize.invokeExact(blob);
            MemorySegment data = (MemorySegment) blobData.invokeExact(blob);
            if (size == 0 || data.equals(MemorySegment.NULL)) {
                return new byte[0];
            }
            if (size > Integer.MAX_VALUE) {
                throw new IllegalStateException("Native Slang blob is too large: " + size);
            }
            return data.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
        } catch (Throwable t) {
            throw new RuntimeException("Reading native Slang blob failed", t);
        } finally {
            destroyBlob(blob);
        }
    }

    private String takeString(MemorySegment blob) {
        return new String(takeBytes(blob), StandardCharsets.UTF_8);
    }

    private void destroyBlob(MemorySegment blob) {
        try {
            blobDestroy.invokeExact(blob);
        } catch (Throwable t) {
            throw new RuntimeException("caustica_slang_blob_destroy failed", t);
        }
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("Slang shim missing export " + name)),
                descriptor);
    }
}
