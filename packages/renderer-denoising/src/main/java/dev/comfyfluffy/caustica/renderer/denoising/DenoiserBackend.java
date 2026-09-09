package dev.comfyfluffy.caustica.renderer.denoising;

/** Fixed-extent denoiser which records work but does not submit or own renderer images. */
public interface DenoiserBackend extends AutoCloseable {
    DenoiserBackendDescriptor descriptor();

    /**
     * Records the selected denoiser into the frame's already recording command buffer. The frame
     * extent must equal the backend descriptor extent. The caller orders input production before
     * this work and output consumption after it, and owns submission and image lifetime through
     * completion. Recorded image transitions restore the layouts supplied in the frame.
     */
    void record(DenoiserFrame frame);

    @Override
    void close();
}
