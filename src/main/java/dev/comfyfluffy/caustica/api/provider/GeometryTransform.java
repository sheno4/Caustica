package dev.comfyfluffy.caustica.api.provider;

/**
 * A mesh-local affine transform whose translation remains double precision until the renderer rebases
 * it. The 3x3 basis is row-major; translation is expressed in world units.
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
    }

    public static GeometryTransform translation(double x, double y, double z) {
        return new GeometryTransform(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, x, y, z);
    }

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
