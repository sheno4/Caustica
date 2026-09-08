package dev.comfyfluffy.caustica.api.geometry;

/**
 * A mesh-local affine transform whose translation remains double precision until the renderer rebases
 * it. The 3x3 basis is row-major; translation is an absolute position in the target scene's units. The
 * renderer applies the current scene-origin rebase when producing float GPU transforms.
 */
public record GeometryTransform(
        float m00, float m01, float m02,
        float m10, float m11, float m12,
        float m20, float m21, float m22,
        double translationX, double translationY, double translationZ
) {
    public GeometryTransform {
        if (!finite(m00, m01, m02, m10, m11, m12, m20, m21, m22)
                || !Double.isFinite(translationX) || !Double.isFinite(translationY)
                || !Double.isFinite(translationZ)) {
            throw new IllegalArgumentException("geometry transform must be finite");
        }
        double determinant = (double) m00 * ((double) m11 * m22 - (double) m12 * m21)
                - (double) m01 * ((double) m10 * m22 - (double) m12 * m20)
                + (double) m02 * ((double) m10 * m21 - (double) m11 * m20);
        if (!Double.isFinite(determinant) || determinant == 0.0) {
            throw new IllegalArgumentException("geometry transform basis must be invertible");
        }
    }

    public static GeometryTransform translation(double x, double y, double z) {
        return new GeometryTransform(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, x, y, z);
    }

    /** Row-major 3x4 GPU transform; subtract the scene origin before narrowing translation to floats. */
    public float[] relativeTo(double originX, double originY, double originZ) {
        return new float[]{
                m00, m01, m02, (float) (translationX - originX),
                m10, m11, m12, (float) (translationY - originY),
                m20, m21, m22, (float) (translationZ - originZ)
        };
    }

    private static boolean finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
