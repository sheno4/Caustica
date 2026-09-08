package dev.comfyfluffy.caustica.api.view;

import java.util.Arrays;

/**
 * Immutable camera pose and projection. Position is absolute in the entry scene's coordinate units;
 * the entry-scene reference and containing medium are carried separately by {@link SceneView}.
 *
 * <p>Matrices contain 16 column-major floats and multiply column vectors. {@code viewFromSceneRotation}
 * is a rigid right-handed rotation from scene axes into view axes, with zero translation; view +X is
 * right, +Y is up, and the camera looks along -Z. For an absolute scene position {@code p}, view position
 * is {@code viewFromSceneRotation * vec4(p - cameraPosition, 1)}. {@code clipFromView} then maps that view
 * position to Vulkan clip coordinates; after division, X and Y cover [-1, 1] and Z covers [0, 1]. It may
 * include the current frame's projection jitter.
 */
public final class Camera {
    public static final Camera IDENTITY = new Camera(0.0, 0.0, 0.0,
            identityMatrix(), identityMatrix());

    private final double x;
    private final double y;
    private final double z;
    private final float[] clipFromView;
    private final float[] viewFromSceneRotation;

    public Camera(double x, double y, double z, float[] clipFromView, float[] viewFromSceneRotation) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("camera position must be finite");
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.clipFromView = copyMatrix(clipFromView, "clipFromView");
        this.viewFromSceneRotation = copyViewRotation(viewFromSceneRotation);
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float[] clipFromView() {
        return clipFromView.clone();
    }

    public float[] viewFromSceneRotation() {
        return viewFromSceneRotation.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Camera camera)) return false;
        return Double.compare(x, camera.x) == 0
                && Double.compare(y, camera.y) == 0
                && Double.compare(z, camera.z) == 0
                && Arrays.equals(clipFromView, camera.clipFromView)
                && Arrays.equals(viewFromSceneRotation, camera.viewFromSceneRotation);
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(x);
        result = 31 * result + Double.hashCode(y);
        result = 31 * result + Double.hashCode(z);
        result = 31 * result + Arrays.hashCode(clipFromView);
        return 31 * result + Arrays.hashCode(viewFromSceneRotation);
    }

    private static float[] copyViewRotation(float[] matrix) {
        float[] copy = copyMatrix(matrix, "viewFromSceneRotation");
        float tolerance = 1.0e-4f;
        if (Math.abs(copy[3]) > tolerance || Math.abs(copy[7]) > tolerance
                || Math.abs(copy[11]) > tolerance || Math.abs(copy[12]) > tolerance
                || Math.abs(copy[13]) > tolerance || Math.abs(copy[14]) > tolerance
                || Math.abs(copy[15] - 1.0f) > tolerance
                || Math.abs(lengthSquared(copy, 0) - 1.0f) > tolerance
                || Math.abs(lengthSquared(copy, 4) - 1.0f) > tolerance
                || Math.abs(lengthSquared(copy, 8) - 1.0f) > tolerance
                || Math.abs(dot(copy, 0, 4)) > tolerance
                || Math.abs(dot(copy, 0, 8)) > tolerance
                || Math.abs(dot(copy, 4, 8)) > tolerance
                || Math.abs(determinant3(copy) - 1.0f) > tolerance) {
            throw new IllegalArgumentException("viewFromSceneRotation must be a rigid right-handed rotation");
        }
        return copy;
    }

    private static float lengthSquared(float[] matrix, int column) {
        return dot(matrix, column, column);
    }

    private static float dot(float[] matrix, int firstColumn, int secondColumn) {
        return matrix[firstColumn] * matrix[secondColumn]
                + matrix[firstColumn + 1] * matrix[secondColumn + 1]
                + matrix[firstColumn + 2] * matrix[secondColumn + 2];
    }

    private static float determinant3(float[] matrix) {
        return matrix[0] * (matrix[5] * matrix[10] - matrix[6] * matrix[9])
                - matrix[4] * (matrix[1] * matrix[10] - matrix[2] * matrix[9])
                + matrix[8] * (matrix[1] * matrix[6] - matrix[2] * matrix[5]);
    }

    private static float[] copyMatrix(float[] matrix, String name) {
        if (matrix == null || matrix.length != 16) {
            throw new IllegalArgumentException(name + " must contain 16 values");
        }
        float[] copy = matrix.clone();
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }
        return copy;
    }

    private static float[] identityMatrix() {
        return new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };
    }
}
