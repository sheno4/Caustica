package dev.comfyfluffy.caustica.renderer.denoising;

/** Fixed-extent denoiser which records work but does not submit or own renderer images. */
public interface DenoiserBackend extends AutoCloseable {
    DenoiserBackendDescriptor descriptor();

    /**
     * Records the selected denoiser into the frame's already recording command buffer. The frame
     * extent must equal the backend descriptor extent.
     */
    void record(DenoiserFrame frame);

    @Override
    void close();
}
