package dev.comfyfluffy.caustica.renderer.denoising;

import java.util.Objects;

/**
 * Device-scoped backend factory. Created backends must be closed before their factory, and a
 * closed factory cannot create more backends.
 */
public interface DenoiserBackendFactory extends AutoCloseable {
    /** Creates a backend whose internal resources remain fixed to {@code descriptor}. */
    DenoiserBackend create(DenoiserBackendDescriptor descriptor);

    /** Rejects a null descriptor before a factory reaches vendor-specific code. */
    static DenoiserBackendDescriptor requireDescriptor(DenoiserBackendDescriptor descriptor) {
        return Objects.requireNonNull(descriptor, "descriptor");
    }

    @Override
    void close();
}
