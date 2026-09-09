package dev.comfyfluffy.caustica.api.geometry;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Immutable four-state Vulkan opacity data in geometry-local triangle order. Each triangle starts on a
 * byte boundary; microtriangles use Vulkan's bird-curve order, two bits each, least significant bits first.
 * States are transparent (0), opaque (1), unknown-transparent (2), and unknown-opaque (3).
 * Known states must hold over the entire microtriangle for every instance and every use of this mesh,
 * including texture filtering and animation. Unknown states run the surface's ordinary coverage shader.
 * Unsupported devices ignore this optional acceleration hint.
 */
public final class OpacityMicromap {
    private final int subdivisionLevel;
    private final int triangleCount;
    private final byte[] data;

    public OpacityMicromap(int subdivisionLevel, int triangleCount, byte[] data) {
        if (subdivisionLevel < 0 || subdivisionLevel > 5) {
            throw new IllegalArgumentException("subdivision level must be in [0,5]");
        }
        if (triangleCount <= 0) throw new IllegalArgumentException("triangleCount must be positive");
        this.subdivisionLevel = subdivisionLevel;
        this.triangleCount = triangleCount;
        if (data.length != Math.multiplyExact(triangleCount, bytesPerTriangle())) {
            throw new IllegalArgumentException("opacity data must cover every triangle");
        }
        this.data = data.clone();
    }

    public int subdivisionLevel() { return subdivisionLevel; }
    public int triangleCount() { return triangleCount; }
    public int bytesPerTriangle() { return Math.max(1, (1 << (2 * subdivisionLevel)) / 4); }
    public int byteSize() { return data.length; }
    public void write(ByteBuffer destination) { destination.put(data); }

    @Override public boolean equals(Object other) {
        return other instanceof OpacityMicromap map && subdivisionLevel == map.subdivisionLevel
                && triangleCount == map.triangleCount && Arrays.equals(data, map.data);
    }

    @Override public int hashCode() {
        return 31 * (31 * subdivisionLevel + triangleCount) + Arrays.hashCode(data);
    }
}
