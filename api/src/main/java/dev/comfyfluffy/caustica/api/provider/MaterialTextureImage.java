package dev.comfyfluffy.caustica.api.provider;

/** One lazily opened, host-canonicalized OpenPBR texture bundle. */
public interface MaterialTextureImage extends AutoCloseable {
    int width();
    int height();
    /** sRGB-encoded base color and linear alpha packed as ARGB8. */
    int albedoArgb(int x, int y);

    /**
     * Mip-zero alpha for one frame declared by {@link MaterialTextureAnalysisSource#alphaFrameCount()}.
     * Interpolated animation alpha must remain within the extrema of the declared frames so their
     * per-texel minimum and maximum are conservative for the whole epoch.
     */
    int alphaArgb(int frame, int x, int y);

    /** Writes semantic OpenPBR values into a caller-reused output object. */
    void readOpenPbr(int x, int y, OpenPbrTextureTexel out);

    @Override
    void close();
}
