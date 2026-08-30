package dev.comfyfluffy.caustica.renderer.denoising;

import java.util.Objects;

/** Fixed-extent denoiser which records work but does not submit or own renderer images. */
public interface DenoiserBackend extends AutoCloseable {
    DenoiserBackendDescriptor descriptor();

    /**
     * Records the selected denoiser into the frame's already recording command buffer. The frame
     * extent must equal the backend descriptor extent.
     */
    void record(DenoiserFrame frame);

    /** Validates the extent invariant shared by every backend implementation. */
    default DenoiserFrame requireCompatibleFrame(DenoiserFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (!descriptor().extent().equals(frame.extent())) {
            throw new IllegalArgumentException("frame extent must match the backend descriptor extent");
        }
        return frame;
    }

    @Override
    void close();
}
