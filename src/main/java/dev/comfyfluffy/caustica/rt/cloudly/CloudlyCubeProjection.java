package dev.comfyfluffy.caustica.rt.cloudly;

/** Vulkan cube layers in +X, -X, +Y, -Y, +Z, -Z order, with viewport Y pointing down. */
public final class CloudlyCubeProjection {
    private CloudlyCubeProjection() { }

    /** Column-major inverse 90-degree projection; clip Z=1 produces a unit-distance face ray. */
    public static float[] fromClip(int face) {
        float[][] right = {{0,0,-1}, {0,0,1}, {1,0,0}, {1,0,0}, {1,0,0}, {-1,0,0}};
        float[][] up = {{0,1,0}, {0,1,0}, {0,0,-1}, {0,0,1}, {0,1,0}, {0,1,0}};
        float[][] forward = {{1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}};
        if (face < 0 || face >= 6) throw new IllegalArgumentException("Invalid cube layer");
        var r = right[face]; var u = up[face]; var f = forward[face];
        return new float[]{r[0],r[1],r[2],0, -u[0],-u[1],-u[2],0,
                0,0,0,1, f[0],f[1],f[2],0};
    }
}
