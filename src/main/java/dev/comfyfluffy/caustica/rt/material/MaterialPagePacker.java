package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialDefaults;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Builds the CPU pixel planes uploaded for one canonical material page. */
final class MaterialPagePacker {
    final List<byte[]> surface0;
    final List<byte[]> normal;
    final List<byte[]> surface1;
    final List<byte[]> emission;
    final List<byte[]> staticAlpha;
    final List<byte[]> temporalAlpha;
    private final int pageSize;
    private final int gutter;

    MaterialPagePacker(int pageSize, int mipCount, int gutter, boolean materialChannels,
                       boolean emissionPresent, boolean staticAlphaPresent, boolean alphaRange) {
        this.pageSize = pageSize;
        this.gutter = gutter;
        surface0 = materialChannels ? allocate(pageSize, mipCount, 255, 0, 0, 0) : null;
        normal = materialChannels ? allocate(pageSize, mipCount, 128, 128, 0, 0) : null;
        surface1 = materialChannels ? allocate(pageSize, mipCount, 255, 255, 255,
                RtMaterialTextureData.unorm8(encodeIor(OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR))) : null;
        emission = emissionPresent ? allocate(pageSize, mipCount, 255, 255, 255, 255) : null;
        staticAlpha = staticAlphaPresent ? allocateR(pageSize, 0) : null;
        temporalAlpha = alphaRange ? allocateRg(pageSize, 1, 0, 255) : null;
    }

    void write(int x, int y, List<RtMaterialTextureData.Level> levels) {
        if (surface0 == null) return;
        for (int mip = 0; mip < levels.size(); mip++) {
            RtMaterialTextureData.Level level = levels.get(mip);
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

    void writeTemporalAlpha(int x, int y, int width, int height, float[] values) {
        blitRg(temporalAlpha.get(0), pageSize, x, y, gutter, width, height, values);
    }

    void writeStaticAlpha(int x, int y, int width, int height, float[] values) {
        blitR(staticAlpha.get(0), pageSize, x, y, gutter, width, height, values);
    }

    private static List<byte[]> allocateR(int size, int value) {
        byte[] values = new byte[size * size];
        Arrays.fill(values, (byte) value);
        return List.of(values);
    }

    private static List<byte[]> allocateRg(int size, int mipCount, int r, int g) {
        List<byte[]> result = new ArrayList<>(mipCount);
        int width = size;
        for (int mip = 0; mip < mipCount; mip++) {
            byte[] values = new byte[width * width * 2];
            for (int i = 0; i < values.length; i += 2) {
                values[i] = (byte) r;
                values[i + 1] = (byte) g;
            }
            result.add(values);
            width = Math.max(1, width / 2);
        }
        return result;
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

    private static void blitR(byte[] dst, int dstWidth, int cx, int cy, int gutter,
                              int srcWidth, int srcHeight, float[] src) {
        for (int dy = -gutter; dy < srcHeight + gutter; dy++) {
            int sy = Math.clamp(dy, 0, srcHeight - 1);
            int ty = cy + dy;
            if (ty < 0 || ty >= dstWidth) continue;
            for (int dx = -gutter; dx < srcWidth + gutter; dx++) {
                int sx = Math.clamp(dx, 0, srcWidth - 1);
                int tx = cx + dx;
                if (tx < 0 || tx >= dstWidth) continue;
                dst[ty * dstWidth + tx] = (byte) RtMaterialTextureData.unorm8(src[(sy * srcWidth + sx) * 4]);
            }
        }
    }

    private static void blitRg(byte[] dst, int dstWidth, int cx, int cy, int gutter,
                               int srcWidth, int srcHeight, float[] src) {
        for (int dy = -gutter; dy < srcHeight + gutter; dy++) {
            int sy = Math.clamp(dy, 0, srcHeight - 1);
            int ty = cy + dy;
            if (ty < 0 || ty >= dstWidth) continue;
            for (int dx = -gutter; dx < srcWidth + gutter; dx++) {
                int sx = Math.clamp(dx, 0, srcWidth - 1);
                int tx = cx + dx;
                if (tx < 0 || tx >= dstWidth) continue;
                int si = (sy * srcWidth + sx) * 4;
                int di = (ty * dstWidth + tx) * 2;
                dst[di] = (byte) RtMaterialTextureData.unorm8(src[si]);
                dst[di + 1] = (byte) RtMaterialTextureData.unorm8(src[si + 1]);
            }
        }
    }

    private static void blit(byte[] dst, int dstWidth, int cx, int cy, int gutter,
                             int srcWidth, int srcHeight, float[] src) {
        for (int dy = -gutter; dy < srcHeight + gutter; dy++) {
            int sy = Math.clamp(dy, 0, srcHeight - 1);
            int ty = cy + dy;
            if (ty < 0 || ty >= dstWidth) continue;
            for (int dx = -gutter; dx < srcWidth + gutter; dx++) {
                int sx = Math.clamp(dx, 0, srcWidth - 1);
                int tx = cx + dx;
                if (tx < 0 || tx >= dstWidth) continue;
                int si = (sy * srcWidth + sx) * 4;
                int di = (ty * dstWidth + tx) * 4;
                dst[di] = (byte) RtMaterialTextureData.unorm8(src[si]);
                dst[di + 1] = (byte) RtMaterialTextureData.unorm8(src[si + 1]);
                dst[di + 2] = (byte) RtMaterialTextureData.unorm8(src[si + 2]);
                dst[di + 3] = (byte) RtMaterialTextureData.unorm8(src[si + 3]);
            }
        }
    }

    static float encodeIor(float ior) {
        return Math.clamp((ior - 1.0f) / (ior + 1.0f), 0.0f, 254.0f / 255.0f);
    }
}
