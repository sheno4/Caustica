package dev.comfyfluffy.caustica.renderer.denoising;

/**
 * Device-scoped backend factory. Created backends must be closed before their factory, and a
 * closed factory cannot create more backends.
 */
public interface DenoiserBackendFactory extends AutoCloseable {
    /** Creates a backend whose internal resources remain fixed to {@code descriptor}. */
    DenoiserBackend create(DenoiserBackendDescriptor descriptor);

    @Override
    void close();
}
