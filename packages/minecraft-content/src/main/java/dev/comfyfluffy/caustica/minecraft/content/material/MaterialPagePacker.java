package dev.comfyfluffy.caustica.minecraft.content.material;

import java.util.ArrayList;
import java.util.List;

/** Builds the CPU pixel planes uploaded for one canonical material page. */
final class MaterialPagePacker {
    final List<byte[]> surface0;
    final List<byte[]> normal;
    final List<byte[]> surface1;
    final List<byte[]> emission;
    private final int pageSize;
    private final int gutter;

    MaterialPagePacker(int pageSize, int mipCount, int gutter, boolean materialChannels,
                       boolean emissionPresent) {
        this.pageSize = pageSize;
        this.gutter = gutter;
        surface0 = materialChannels ? allocate(pageSize, mipCount, 255, 0, 0, 0) : null;
        normal = materialChannels ? allocate(pageSize, mipCount, 128, 128, 0, 0) : null;
        surface1 = materialChannels ? allocate(pageSize, mipCount, 255, 255, 255,
                MaterialTextureLevels.unorm8(encodeIor(OpenPbrDefaults.SPECULAR_IOR))) : null;
        emission = emissionPresent ? allocate(pageSize, mipCount, 255, 255, 255, 255) : null;
    }

    /** Writes only this placement's padded rectangle; aligned planner cells keep parallel writes disjoint. */
    void write(int x, int y, List<MaterialTextureLevels.Level> levels) {
        if (surface0 == null) return;
        for (int mip = 0; mip < levels.size(); mip++) {
            MaterialTextureLevels.Level level = levels.get(mip);
            int width = Math.max(1, pageSize >> mip);
            int cx = x >> mip;
            int cy = y >> mip;
            int mipGutter = Math.max(1, gutter >> mip);
            blit(surface0.get(mip), width, cx, cy, mipGutter, level.width(), level.height(), level.surface0());
            blit(normal.get(mip), width, cx, cy, mipGutter, level.width(), level.height(), level.normal());
            blit(surface1.get(mip), width, cx, cy, mipGutter, level.width(), level.height(), level.surface1());
            if (emission != null) {
                blit(emission.get(mip), width, cx, cy, mipGutter, level.width(), level.height(),
                        level.emissionColor());
            }
        }
    }

    private static List<byte[]> allocate(int size, int mipCount, int r, int g, int b, int a) {
        List<byte[]> result = new ArrayList<>(mipCount);
        int width = size;
        for (int mip = 0; mip < mipCount; mip++) {
            byte[] values = new byte[width * width * 4];
            for (int i = 0; i < values.length; i += 4) {
                values[i] = (byte) r;
                values[i + 1] = (byte) g;
                values[i + 2] = (byte) b;
                values[i + 3] = (byte) a;
            }
            result.add(values);
            width = Math.max(1, width / 2);
        }
        return result;
    }

    private static void blit(byte[] dst, int dstWidth, int cx, int cy, int gutter,
                             int srcWidth, int srcHeight, float[] src) {
        int left = Math.max(0, cx - gutter);
        int top = Math.max(0, cy - gutter);
        int right = Math.min(dstWidth, cx + srcWidth + gutter);
        int bottom = Math.min(dstWidth, cy + srcHeight + gutter);
        for (int y = top; y < bottom; y++) {
            int sy = Math.clamp(y - cy, 0, srcHeight - 1);
            for (int x = left; x < right; x++) {
                int sx = Math.clamp(x - cx, 0, srcWidth - 1);
                int si = (sy * srcWidth + sx) * 4;
                int di = (y * dstWidth + x) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    dst[di + channel] = (byte) MaterialTextureLevels.unorm8(src[si + channel]);
                }
            }
        }
    }

    /** Store amplitude reflectance; one unorm8 step below one keeps decoded IOR finite. */
    static float encodeIor(float ior) {
        return Math.clamp((ior - 1.0f) / (ior + 1.0f), 0.0f, 254.0f / 255.0f);
    }
}
