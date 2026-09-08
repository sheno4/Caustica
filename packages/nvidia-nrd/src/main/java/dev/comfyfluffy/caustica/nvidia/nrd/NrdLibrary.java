package dev.comfyfluffy.caustica.nvidia.nrd;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;

/** FFM binding for the package-owned NRD/NRI Vulkan shim. */
public final class NrdLibrary implements NrdNative {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final String NRD_REVISION = "b233cc3ec5b1db2763e45fd18c9bb19793016355";
    private final MethodHandle create;
    private final MethodHandle record;
    private final MethodHandle destroy;
    private final MethodHandle lastError;

    private NrdLibrary(SymbolLookup lookup) {
        create = handle(lookup, "nrdshim_create", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        record = handle(lookup, "nrdshim_record", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        destroy = handle(lookup, "nrdshim_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        lastError = handle(lookup, "nrdshim_last_error", FunctionDescriptor.of(ValueLayout.ADDRESS));
    }

    public static NrdLibrary load(Path shim) {
        Objects.requireNonNull(shim, "shim");
        return new NrdLibrary(SymbolLookup.libraryLookup(shim.toAbsolutePath().normalize(), Arena.global()));
    }

    /** Extracts the package's current-platform shim and NVIDIA license into a revision-scoped directory. */
    public static NrdLibrary loadBundled(Path runtimeDirectory) {
        Objects.requireNonNull(runtimeDirectory, "runtimeDirectory");
        Platform platform = Platform.current();
        String root = "/caustica/natives/nrd/" + NRD_REVISION + "/" + platform.directory() + "/";
        Path destination = runtimeDirectory.toAbsolutePath().normalize().resolve("nrd").resolve(NRD_REVISION)
                .resolve(platform.directory());
        try {
            Files.createDirectories(destination);
            extract(root + platform.library(), destination.resolve(platform.library()));
            extract(root + "LICENSE.txt", destination.resolve("LICENSE.txt"));
            extract(root + "NRI_LICENSE.txt", destination.resolve("NRI_LICENSE.txt"));
        } catch (IOException failure) {
            throw new NrdException("extracting bundled NRD natives failed", failure);
        }
        return load(destination.resolve(platform.library()));
    }

    @Override public MemorySegment create(MemorySegment description) {
        try { return (MemorySegment) create.invokeExact(description); }
        catch (Throwable failure) { throw invocationFailure("nrdshim_create", failure); }
    }

    @Override public int record(MemorySegment instance, long commandBuffer, MemorySegment common, MemorySegment resources) {
        try { return (int) record.invokeExact(instance, commandBuffer, common, resources); }
        catch (Throwable failure) { throw invocationFailure("nrdshim_record", failure); }
    }

    @Override public void destroy(MemorySegment instance) {
        try { destroy.invokeExact(instance); }
        catch (Throwable failure) { throw invocationFailure("nrdshim_destroy", failure); }
    }

    @Override public String lastError() {
        try {
            MemorySegment text = (MemorySegment) lastError.invokeExact();
            return text.equals(MemorySegment.NULL) ? "" : text.reinterpret(4096).getString(0);
        } catch (Throwable failure) {
            throw invocationFailure("nrdshim_last_error", failure);
        }
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("NRD shim missing export " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    private static NrdException invocationFailure(String function, Throwable cause) {
        return new NrdException(function + " invocation failed", cause);
    }

    private static void extract(String resource, Path destination) throws IOException {
        byte[] bytes;
        try (InputStream input = NrdLibrary.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("missing bundled resource " + resource);
            bytes = input.readAllBytes();
        }
        if (Files.isRegularFile(destination) && Arrays.equals(bytes, Files.readAllBytes(destination))) return;
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record Platform(String directory, String library) {
        static Platform current() {
            String architecture = System.getProperty("os.arch", "").toLowerCase();
            if (!architecture.equals("amd64") && !architecture.equals("x86_64")) {
                throw new NrdException("NRD natives are unavailable for architecture " + architecture);
            }
            String operatingSystem = System.getProperty("os.name", "").toLowerCase();
            if (operatingSystem.contains("windows")) return new Platform("windows-x64", "nrdshim.dll");
            if (operatingSystem.contains("linux")) return new Platform("linux-x64", "libnrdshim.so");
            throw new NrdException("NRD natives are unavailable for " + operatingSystem);
        }
    }
}
