package dev.comfyfluffy.caustica.engine.material;

/** One lazily opened, host-canonicalized OpenPBR texture bundle. */
public interface MaterialTextureImage extends AutoCloseable {
    int width();
    int height();
    int albedoArgb(int x, int y);

    /** Number of distinct mip-zero alpha frames, exhaustive for the resource epoch. */
    int alphaFrameCount();

    /**
     * Mip-zero alpha for one exhaustive frame. Interpolated animation alpha must remain within the extrema
     * of the supplied frames so their per-texel minimum and maximum are conservative for the whole epoch.
     */
    int alphaArgb(int frame, int x, int y);

    /** Writes semantic OpenPBR values into a caller-reused output object. */
    void readOpenPbr(int x, int y, OpenPbrTextureTexel out);

    @Override
    void close();
}
