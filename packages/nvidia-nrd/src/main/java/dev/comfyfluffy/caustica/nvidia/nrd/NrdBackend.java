package dev.comfyfluffy.caustica.nvidia.nrd;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackend;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendDescriptor;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserExtent;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserFrame;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/** NRD/NRI Vulkan backend. The caller owns command submission and every user image. */
public final class NrdBackend implements DenoiserBackend {
    private final NrdNative nativeApi;
    private final NrdMethod method;
    private final DenoiserBackendDescriptor descriptor;
    private MemorySegment handle;

    NrdBackend(NrdNative nativeApi, NrdDevice device, NrdMethod method, int width, int height) {
        this.nativeApi = Objects.requireNonNull(nativeApi, "nativeApi");
        Objects.requireNonNull(device, "device");
        this.method = Objects.requireNonNull(method, "method");
        requireExtent(width, height);
        descriptor = new DenoiserBackendDescriptor(new DenoiserExtent(width, height),
                method == NrdMethod.REBLUR_DIFFUSE_SPECULAR
                        ? DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE
                        : DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment create = arena.allocate(NrdAbi.CREATE_SIZE, 8);
            NrdAbi.writeCreate(create, device, method, width, height);
            handle = nativeApi.create(create);
        }
        if (handle.equals(MemorySegment.NULL)) throw failure("creating " + method);
    }

    public static NrdBackend open(NrdLibrary library, NrdDevice device, NrdMethod method, int width, int height) {
        return new NrdBackend(Objects.requireNonNull(library, "library"), device, method, width, height);
    }

    public NrdMethod method() { return method; }
    @Override public DenoiserBackendDescriptor descriptor() { return descriptor; }

    @Override
    public void record(DenoiserFrame frame) {
        requireOpen();
        frame = requireCompatibleFrame(frame);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment common = arena.allocate(NrdAbi.COMMON_SIZE, 4);
            MemorySegment resources = arena.allocate(NrdAbi.RESOURCES_SIZE, 8);
            NrdAbi.writeCommon(common, frame.common());
            NrdAbi.writeResources(resources, frame.inputs());
            if (nativeApi.record(handle, frame.commandBuffer(), common, resources) != 0) {
                throw failure("recording NRD");
            }
        }
    }

    @Override
    public void close() {
        if (!handle.equals(MemorySegment.NULL)) {
            nativeApi.destroy(handle);
            handle = MemorySegment.NULL;
        }
    }

    private NrdException failure(String operation) {
        String detail = nativeApi.lastError();
        return new NrdException(operation + " failed" + (detail == null || detail.isBlank() ? "" : ": " + detail));
    }

    private void requireOpen() {
        if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("NRD backend is closed");
    }

    private static void requireExtent(int width, int height) {
        if (width < 1 || width > 65_535 || height < 1 || height > 65_535) {
            throw new IllegalArgumentException("NRD extent must be in [1, 65535]");
        }
    }
}
