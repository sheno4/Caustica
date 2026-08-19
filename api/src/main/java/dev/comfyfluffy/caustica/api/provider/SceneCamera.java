package dev.comfyfluffy.caustica.api.provider;

import java.util.Arrays;

/** Immutable camera state supplied with one retained-scene frame. */
public record SceneCamera(double x, double y, double z, float[] projection, float[] viewRotation) {
    public static final SceneCamera IDENTITY = new SceneCamera(0.0, 0.0, 0.0,
            identityMatrix(), identityMatrix());

    public SceneCamera {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("camera position must be finite");
        }
        projection = copyMatrix(projection, "projection");
        viewRotation = copyMatrix(viewRotation, "view rotation");
    }

    @Override
    public float[] projection() {
        return projection.clone();
    }

    @Override
    public float[] viewRotation() {
        return viewRotation.clone();
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
        float[] matrix = new float[16];
        Arrays.fill(matrix, 0.0f);
        matrix[0] = 1.0f;
        matrix[5] = 1.0f;
        matrix[10] = 1.0f;
        matrix[15] = 1.0f;
        return matrix;
    }
}
