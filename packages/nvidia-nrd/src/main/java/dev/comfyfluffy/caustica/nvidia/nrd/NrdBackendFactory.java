package dev.comfyfluffy.caustica.nvidia.nrd;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackend;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendDescriptor;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;

import java.util.Objects;

/** Device-scoped factory for fixed-extent NRD backends. */
public final class NrdBackendFactory implements DenoiserBackendFactory {
    private final NrdNative nativeApi;
    private final NrdDevice device;
    private boolean closed;

    NrdBackendFactory(NrdNative nativeApi, NrdDevice device) {
        this.nativeApi = Objects.requireNonNull(nativeApi, "nativeApi");
        this.device = Objects.requireNonNull(device, "device");
    }

    public static NrdBackendFactory open(NrdLibrary library, NrdDevice device) {
        return new NrdBackendFactory(Objects.requireNonNull(library, "library"), device);
    }

    @Override
    public DenoiserBackend create(DenoiserBackendDescriptor descriptor) {
        if (closed) throw new IllegalStateException("NRD backend factory is closed");
        NrdMethod method = descriptor.signalEncoding() == DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE
                ? NrdMethod.REBLUR_DIFFUSE_SPECULAR : NrdMethod.RELAX_DIFFUSE_SPECULAR;
        return new NrdBackend(nativeApi, device, method,
                descriptor.extent().width(), descriptor.extent().height());
    }

    @Override public void close() { closed = true; }
}
