package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.OpacityMicromap;
import java.util.Arrays;

/** Conservative texture-region classification packed into Vulkan's four-state bird curve. */
public final class TerrainOpacityBaker {
    public static final int LEVEL = 3;
    public static final int TRANSPARENT = 0;
    public static final int OPAQUE = 1;
    public static final int UNKNOWN = 3;
    private static final int GRID = 1 << LEVEL;
    private static final int TRIANGLE_BYTES = GRID * GRID / 4;

    private TerrainOpacityBaker() { }

    @FunctionalInterface
    public interface RegionClassifier {
        /** Returns a known state only if it holds throughout the closed UV rectangle for every frame. */
        int classify(int triangle, float minU, float minV, float maxU, float maxV);
    }

    public static OpacityMicromap bake(float[] cornerUvs, int firstTriangle, int triangleCount,
                                       RegionClassifier classifier) {
        byte[] data = new byte[Math.multiplyExact(triangleCount, TRIANGLE_BYTES)];
        Arrays.fill(data, (byte) 0xff);
        boolean useful = false;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int source = firstTriangle + triangle;
            for (int y = 0; y < GRID; y++) {
                for (int x = 0; x < GRID - y; x++) {
                    useful |= write(data, triangle, source, cornerUvs, classifier, x, y, false);
                    if (x + y < GRID - 1) useful |= write(data, triangle, source, cornerUvs, classifier, x, y, true);
                }
            }
        }
        return useful ? new OpacityMicromap(LEVEL, triangleCount, data) : null;
    }

    private static boolean write(byte[] data, int triangle, int source, float[] uv, RegionClassifier classifier,
                                  int x, int y, boolean upper) {
        float u0 = interpolate(uv, source * 6, upper ? x + 1 : x, upper ? y + 1 : y);
        float v0 = interpolate(uv, source * 6 + 1, upper ? x + 1 : x, upper ? y + 1 : y);
        float u1 = interpolate(uv, source * 6, x + 1, y);
        float v1 = interpolate(uv, source * 6 + 1, x + 1, y);
        float u2 = interpolate(uv, source * 6, x, y + 1);
        float v2 = interpolate(uv, source * 6 + 1, x, y + 1);
        int state = classifier.classify(source, Math.min(u0, Math.min(u1, u2)), Math.min(v0, Math.min(v1, v2)),
                Math.max(u0, Math.max(u1, u2)), Math.max(v0, Math.max(v1, v2)));
        int index = birdIndex(x, y, upper, LEVEL);
        int offset = triangle * TRIANGLE_BYTES + index / 4;
        int shift = 2 * (index % 4);
        data[offset] = (byte) ((data[offset] & ~(3 << shift)) | state << shift);
        return state == OPAQUE || state == TRANSPARENT;
    }

    private static float interpolate(float[] uv, int offset, int x, int y) {
        return uv[offset] + (uv[offset + 2] - uv[offset]) * (x / (float) GRID)
                + (uv[offset + 4] - uv[offset]) * (y / (float) GRID);
    }

    static int birdIndex(int u, int v, boolean upper, int level) {
        int w = ~(u + v) - (upper ? 1 : 0);
        int b0 = ~(u ^ w) & ((1 << level) - 1);
        int t = (u ^ v) & b0;
        int f = t;
        f ^= f >>> 1;
        f ^= f >>> 2;
        f ^= f >>> 4;
        f ^= f >>> 8;
        int b1 = ((f ^ u) & ~b0) | t;
        return interleave(b0) | (interleave(b1) << 1);
    }

    private static int interleave(int value) {
        value = (value | value << 8) & 0x00ff00ff;
        value = (value | value << 4) & 0x0f0f0f0f;
        value = (value | value << 2) & 0x33333333;
        return (value | value << 1) & 0x55555555;
    }

    /** Includes bilinear neighbors and floating-point uncertainty; atlas-edge footprints remain unknown. */
    public static int classifyRegion(int width, int height, int frames, AlphaReader alpha,
                                      float minU, float minV, float maxU, float maxV, float cutoff) {
        int x0 = (int) Math.floor(Math.nextDown(minU * width - 0.5f));
        int y0 = (int) Math.floor(Math.nextDown(minV * height - 0.5f));
        int x1 = (int) Math.floor(Math.nextUp(maxU * width - 0.5f)) + 1;
        int y1 = (int) Math.floor(Math.nextUp(maxV * height - 0.5f)) + 1;
        if (x0 < 0 || y0 < 0 || x1 >= width || y1 >= height) return UNKNOWN;
        int state = -1;
        for (int frame = 0; frame < frames; frame++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int sample = alpha.alpha(frame, x, y) / 255f >= cutoff ? OPAQUE : TRANSPARENT;
                    if (state != -1 && state != sample) return UNKNOWN;
                    state = sample;
                }
            }
        }
        return state;
    }

    @FunctionalInterface
    public interface AlphaReader { int alpha(int frame, int x, int y); }
}
