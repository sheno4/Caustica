package dev.comfyfluffy.caustica.spi.host;

/**
 * Narrow descriptor-write capability supplied to the first-party renderer host adapter.
 * This host integration SPI is not supported extension API.
 */
public interface BaseColorTextureSink {
    /** Bind an image view and sampler to a stable base-color texture slot. */
    void setBaseColorTexture(int textureIndex, long imageView, long sampler);
}
