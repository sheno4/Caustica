package dev.comfyfluffy.caustica.renderer.presentation.fog;

/** Immutable world-space lattice. Voxels are density, humidity, coverage, ground height; x varies fastest, then z, then y. */
public record FogField(double originX, double originY, double originZ, float spacing,
                       int sizeX, int sizeY, int sizeZ, float[] voxels) {
    public FogField {
        voxels = voxels.clone();
    }
    @Override public float[] voxels() { return voxels.clone(); }
}
