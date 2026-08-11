package dev.comfyfluffy.caustica.engine.material;

/** One lazily opened, host-canonicalized OpenPBR texture bundle. */
public interface MaterialTextureImage extends AutoCloseable {
    int width();
    int height();
    int albedoArgb(int x, int y);

    /** Writes semantic OpenPBR values into a caller-reused output object. */
    void readOpenPbr(int x, int y, OpenPbrTextureTexel out);

    @Override
    void close();
}
