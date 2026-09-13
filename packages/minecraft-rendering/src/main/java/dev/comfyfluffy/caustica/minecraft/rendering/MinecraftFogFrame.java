package dev.comfyfluffy.caustica.minecraft.rendering;

import java.nio.FloatBuffer;

/** World-space fog inputs captured independently of celestial texture availability. */
public record MinecraftFogFrame(MinecraftCelestialFrame celestial, double animationSeconds, Grid grid) {
    /** Smooth daily multiplier: clear at noon, denser overnight, with a peak around dawn. */
    public float dailyDensity() {
        return dailyDensity(celestial.sunAngleRadians());
    }

    public static float dailyDensity(double sunAngleRadians) {
        double night = (1.0 - Math.cos(sunAngleRadians)) * 0.5;
        double dawn = Math.exp(5.0 * (-Math.sin(sunAngleRadians) - 1.0));
        return (float) (0.25 + 0.65 * night + 0.65 * dawn);
    }

    /**
     * Immutable regular lattice in absolute block coordinates. Voxels are ordered x, then z, then y.
     * Each voxel contains density multiplier, humidity, and sky exposure; unknown columns contain zeroes.
     * Terrain heights use x-then-z order and describe the top motion-blocking surface without leaves.
     */
    public static final class Grid {
        public static final int COMPONENTS = 3;
        private final double originX;
        private final double originY;
        private final double originZ;
        private final float spacing;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final float[] coefficients;
        private final float[] terrainHeights;

        public Grid(double originX, double originY, double originZ, float spacing,
                    int sizeX, int sizeY, int sizeZ, float[] coefficients, float[] terrainHeights) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.spacing = spacing;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.coefficients = coefficients.clone();
            this.terrainHeights = terrainHeights.clone();
        }

        public double originX() { return originX; }
        public double originY() { return originY; }
        public double originZ() { return originZ; }
        public float spacing() { return spacing; }
        public int sizeX() { return sizeX; }
        public int sizeY() { return sizeY; }
        public int sizeZ() { return sizeZ; }
        public int voxelCount() { return sizeX * sizeY * sizeZ; }
        public float density(int index) { return coefficients[index * COMPONENTS]; }
        public float humidity(int index) { return coefficients[index * COMPONENTS + 1]; }
        public float exposure(int index) { return coefficients[index * COMPONENTS + 2]; }
        public float terrainHeight(int column) { return terrainHeights[column]; }

        /** Writes RGBA voxels: density, humidity, sky exposure, terrain height in world blocks. */
        public void writeVoxels(FloatBuffer target) {
            int columns = sizeX * sizeZ;
            for (int index = 0; index < voxelCount(); index++) {
                target.put(density(index)).put(humidity(index)).put(exposure(index))
                        .put(terrainHeights[index % columns]);
            }
        }
    }
}
