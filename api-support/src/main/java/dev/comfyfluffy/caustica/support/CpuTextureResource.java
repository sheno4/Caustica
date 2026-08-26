package dev.comfyfluffy.caustica.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable, tightly packed RGBA8 texture content copied from provider-owned CPU memory. */
public final class CpuTextureResource {
    public enum Encoding {
        /** RGB bytes already encode linear values; alpha is always linear. */
        LINEAR,
        /** RGB bytes use the sRGB transfer function and are decoded once by the sampled image view. */
        SRGB
    }

    private final Encoding encoding;
    private final List<MipLevel> mipLevels;

    /** A single-level texture with no mip chain. */
    public static CpuTextureResource single(int width, int height, Encoding encoding, byte[] rgba8) {
        return new CpuTextureResource(encoding, List.of(new MipLevel(width, height, rgba8)));
    }

    /** Copy a provider-authored mip chain into renderer-owned CPU memory. */
    public CpuTextureResource(Encoding encoding, List<MipLevel> mipLevels) {
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(mipLevels, "mipLevels");
        if (mipLevels.isEmpty()) throw new IllegalArgumentException("texture needs at least one mip level");
        ArrayList<MipLevel> copied = new ArrayList<>(mipLevels.size());
        for (int index = 0; index < mipLevels.size(); index++) {
            MipLevel level = Objects.requireNonNull(mipLevels.get(index), "mip level");
            if (index > 0) {
                MipLevel previous = copied.get(index - 1);
                if (previous.width() == 1 && previous.height() == 1) {
                    throw new IllegalArgumentException("mip chain continues past 1x1");
                }
                if (level.width() != Math.max(1, previous.width() / 2)
                        || level.height() != Math.max(1, previous.height() / 2)) {
                    throw new IllegalArgumentException("mip level dimensions do not follow the base level");
                }
            }
            copied.add(level);
        }
        this.mipLevels = List.copyOf(copied);
    }

    public int width() {
        return mipLevels.getFirst().width();
    }

    public int height() {
        return mipLevels.getFirst().height();
    }

    public Encoding encoding() {
        return encoding;
    }

    public byte[] rgba8() {
        return mipLevels.getFirst().rgba8();
    }

    public List<MipLevel> mipLevels() {
        return mipLevels;
    }

    /** One immutable, tightly packed RGBA8 mip level. */
    public static final class MipLevel {
        private final int width;
        private final int height;
        private final byte[] rgba8;

        public MipLevel(int width, int height, byte[] rgba8) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("texture dimensions must be positive");
            }
            this.width = width;
            this.height = height;
            this.rgba8 = Objects.requireNonNull(rgba8, "rgba8").clone();
            int expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            if (this.rgba8.length != expected) {
                throw new IllegalArgumentException("RGBA8 byte count does not match texture dimensions");
            }
        }

        public int width() { return width; }
        public int height() { return height; }
        public byte[] rgba8() { return rgba8.clone(); }
    }
}
