package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.OpacityMicromap;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftOpacityBounds;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.function.IntFunction;

/** Worker-owned reuse of immutable material/UV micromaps, bounded independently of streamed terrain. */
public final class TerrainOpacityCache {
    private static final int CAPACITY = 4096;
    private static final int TRIANGLE_BYTES = (1 << (2 * TerrainOpacityBaker.LEVEL)) / 4;
    private record Key(MinecraftOpacityBounds bounds, float cutoff,
                       float u0, float v0, float u1, float v1, float u2, float v2) { }
    private record Entry(byte[] data, boolean useful) { }
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(CAPACITY, .75f, true);
    private Object epoch;

    /** A material lookup owns the immutable alpha bounds for one resource epoch. */
    public void beginEpoch(Object current) {
        if (epoch != current) {
            entries.clear();
            epoch = current;
        }
    }

    public OpacityMicromap bake(float[] uv, int firstTriangle, int triangleCount,
                               IntFunction<MinecraftOpacityBounds> materials, float cutoff) {
        byte[] data = new byte[Math.multiplyExact(triangleCount, TRIANGLE_BYTES)];
        boolean useful = false;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int source = firstTriangle + triangle, offset = source * 6;
            var bounds = materials.apply(source);
            var key = new Key(bounds, cutoff, uv[offset], uv[offset + 1], uv[offset + 2],
                    uv[offset + 3], uv[offset + 4], uv[offset + 5]);
            Entry entry = entries.get(key);
            if (entry == null) {
                var map = TerrainOpacityBaker.bake(uv, source, 1, (index, minU, minV, maxU, maxV) -> {
                    if (bounds == null) return TerrainOpacityBaker.UNKNOWN;
                    var transform = bounds.uv();
                    return TerrainOpacityBaker.classifyRegion(bounds.width(), bounds.height(), 2, bounds::alpha,
                            (minU - transform.u()) * transform.inverseDu(),
                            (minV - transform.v()) * transform.inverseDv(),
                            (maxU - transform.u()) * transform.inverseDu(),
                            (maxV - transform.v()) * transform.inverseDv(), cutoff);
                });
                byte[] packed = new byte[TRIANGLE_BYTES];
                if (map == null) Arrays.fill(packed, (byte) 0xff);
                else map.write(ByteBuffer.wrap(packed));
                entry = new Entry(packed, map != null);
                if (entries.size() == CAPACITY) entries.pollFirstEntry();
                entries.put(key, entry);
            }
            System.arraycopy(entry.data, 0, data, triangle * TRIANGLE_BYTES, TRIANGLE_BYTES);
            useful |= entry.useful;
        }
        return useful ? new OpacityMicromap(TerrainOpacityBaker.LEVEL, triangleCount, data) : null;
    }
}
