package dev.comfyfluffy.caustica.api.provider;

import java.util.Objects;

/** Immutable, tightly packed RGBA8 texture content copied from provider-owned CPU memory. */
public final class CpuTextureResource implements TextureResource {
    public enum Encoding {
        /** RGB bytes already encode linear values; alpha is always linear. */
        LINEAR,
        /** RGB bytes use the sRGB transfer function and are decoded once by the sampled image view. */
        SRGB
    }

    private final int width;
    private final int height;
    private final Encoding encoding;
    private final byte[] rgba8;

    public CpuTextureResource(int width, int height, Encoding encoding, byte[] rgba8) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture dimensions must be positive");
        }
        this.width = width;
        this.height = height;
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        this.rgba8 = Objects.requireNonNull(rgba8, "rgba8").clone();
        int expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (this.rgba8.length != expected) {
            throw new IllegalArgumentException("RGBA8 byte count does not match texture dimensions");
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Encoding encoding() {
        return encoding;
    }

    public byte[] rgba8() {
        return rgba8.clone();
    }
}
