package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Camera interoperability data; source coordinates are X, negative host Z, host Y in metres. */
public final class CloudlyFrameCamera {
    public static final int BYTE_SIZE = 80;
    private final float[] fromClip;
    private final float[] sourceOrigin;
    private final float metersPerSceneUnit;

    public CloudlyFrameCamera(float[] cameraRelativeFromClip, double x, double y, double z,
                             double seaLevel, double metersPerSceneUnit) {
        if (cameraRelativeFromClip.length != 16 || metersPerSceneUnit <= 0) {
            throw new IllegalArgumentException("Cloudly camera needs a complete inverse projection and positive scene scale");
        }
        this.fromClip = cameraRelativeFromClip.clone();
        this.metersPerSceneUnit = (float) metersPerSceneUnit;
        this.sourceOrigin = new float[]{(float) (x * metersPerSceneUnit), (float) (-z * metersPerSceneUnit),
                (float) ((y - seaLevel) * metersPerSceneUnit)};
    }

    /** The frame owns this payload until the GPU has finished reading its camera ray transform. */
    public void write(ByteBuffer target) {
        if (target.remaining() != BYTE_SIZE) throw new IllegalArgumentException("Wrong Cloudly camera bridge size");
        ByteBuffer bytes = target.slice().order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < 16; index++) bytes.putFloat(index * 4, fromClip[index]);
        for (int index = 0; index < 3; index++) bytes.putFloat(64 + index * 4, sourceOrigin[index]);
        bytes.putFloat(76, metersPerSceneUnit);
    }

    /** Pixel-center direction matching the host inverse projection, without an Unreal handedness assumption. */
    public float[] direction(float clipX, float clipY) {
        float[] host = unproject(clipX, clipY);
        double length = Math.sqrt((double) host[0] * host[0] + (double) host[1] * host[1] + (double) host[2] * host[2]);
        return new float[]{(float) (host[0] / length), (float) (-host[2] / length), (float) (host[1] / length)};
    }

    /** Original cone-width calculations consume vertical field of view in radians. */
    public float verticalFieldOfView() {
        float[] top = direction(0, -1), bottom = direction(0, 1);
        double cosine = (double) top[0] * bottom[0] + (double) top[1] * bottom[1] + (double) top[2] * bottom[2];
        return (float) Math.acos(Math.clamp(cosine, -1, 1));
    }

    public float[] sourceOriginMeters() { return sourceOrigin.clone(); }

    public float horizontalFieldOfView() {
        float[] left = direction(-1, 0), right = direction(1, 0);
        return (float) Math.acos(Math.clamp((double) left[0] * right[0] + (double) left[1] * right[1]
                + (double) left[2] * right[2], -1, 1));
    }

    /** Source HLSL uses row-vector multiplication and its reflected buffers store matrix columns. */
    public float[] worldFromClipForSourceUniform() { return transpose(worldFromClip()); }

    public float[] worldToClipForSourceUniform() { return transpose(inverse(worldFromClip())); }

    public float[] cameraFromClipForSourceUniform() { return transpose(cameraFromClip()); }

    public float[] cameraToClipForSourceUniform() { return transpose(inverse(cameraFromClip())); }

    /** The original temporal shader maps current clip coordinates into the previous camera's clip space. */
    public float[] clipToPreviousForSourceUniform(CloudlyFrameCamera previous) {
        return transpose(multiply(inverse(previous.worldFromClip()), worldFromClip()));
    }

    /** View forward is positive Z in the source's centimetre depth conversion. */
    public float[] viewFromTranslatedWorldForSourceUniform() {
        float[] forward = direction(0, 0), right = orthogonal(direction(1, 0), forward);
        float[] up = orthogonal(direction(0, -1), forward);
        return new float[]{right[0],right[1],right[2],0, up[0],up[1],up[2],0,
                forward[0],forward[1],forward[2],0, 0,0,0,1};
    }

    public float[] sourceRight() { return orthogonal(direction(1, 0), direction(0, 0)); }

    public float[] sourceUp() { return orthogonal(direction(0, -1), direction(0, 0)); }

    private static float[] orthogonal(float[] direction, float[] forward) {
        double projection = (double) direction[0] * forward[0] + (double) direction[1] * forward[1] + (double) direction[2] * forward[2];
        double x = direction[0] - projection * forward[0], y = direction[1] - projection * forward[1], z = direction[2] - projection * forward[2];
        double length = Math.sqrt(x*x + y*y + z*z);
        return new float[]{(float)(x/length),(float)(y/length),(float)(z/length)};
    }

    /** Coefficients consumed by the source's ConvertFromDeviceZ, producing centimetres along view forward. */
    public float[] deviceDepthToViewCentimeters() {
        float[] forward = unproject(0, 0);
        double length = Math.sqrt((double) forward[0] * forward[0] + (double) forward[1] * forward[1]
                + (double) forward[2] * forward[2]);
        double numerator = ((double) fromClip[12] * forward[0] + (double) fromClip[13] * forward[1]
                + (double) fromClip[14] * forward[2]) / length * metersPerSceneUnit * 100;
        return new float[]{0, 0, (float) (fromClip[11] / numerator), (float) (-fromClip[15] / numerator)};
    }

    private float[] cameraFromClip() {
        float[] matrix = new float[16];
        float scale = metersPerSceneUnit * 100;
        for (int column = 0; column < 4; column++) {
            int offset = column * 4;
            // Source viewport-to-screen coordinates have upward positive clip Y.
            float clipScale = column == 1 ? -1 : 1;
            matrix[offset] = fromClip[offset] * scale * clipScale;
            matrix[offset + 1] = -fromClip[offset + 2] * scale * clipScale;
            matrix[offset + 2] = fromClip[offset + 1] * scale * clipScale;
            matrix[offset + 3] = fromClip[offset + 3] * clipScale;
        }
        return matrix;
    }

    private float[] worldFromClip() {
        float[] matrix = cameraFromClip();
        for (int column = 0; column < 4; column++) for (int row = 0; row < 3; row++) {
            int offset = column * 4;
            matrix[offset + row] += sourceOrigin[row] * 100 * matrix[offset + 3];
        }
        return matrix;
    }

    private static float[] transpose(float[] matrix) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
            result[column * 4 + row] = matrix[row * 4 + column];
        }
        return result;
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
            double value = 0;
            for (int index = 0; index < 4; index++) value += (double) left[index * 4 + row] * right[column * 4 + index];
            result[column * 4 + row] = (float) value;
        }
        return result;
    }

    private static float[] inverse(float[] matrix) {
        double[][] rows = new double[4][8];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) rows[row][column] = matrix[column * 4 + row];
            rows[row][row + 4] = 1;
        }
        for (int column = 0; column < 4; column++) {
            int pivot = column;
            for (int row = column + 1; row < 4; row++) if (Math.abs(rows[row][column]) > Math.abs(rows[pivot][column])) pivot = row;
            if (rows[pivot][column] == 0) throw new IllegalArgumentException("Cloudly camera transform is singular");
            double[] swapped = rows[column]; rows[column] = rows[pivot]; rows[pivot] = swapped;
            double divisor = rows[column][column];
            for (int index = 0; index < 8; index++) rows[column][index] /= divisor;
            for (int row = 0; row < 4; row++) if (row != column) {
                double factor = rows[row][column];
                for (int index = 0; index < 8; index++) rows[row][index] -= factor * rows[column][index];
            }
        }
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) result[column * 4 + row] = (float) rows[row][column + 4];
        return result;
    }

    private float[] unproject(float x, float y) {
        float w = fromClip[3] * x + fromClip[7] * y + fromClip[11] + fromClip[15];
        return new float[]{(fromClip[0] * x + fromClip[4] * y + fromClip[8] + fromClip[12]) / w,
                (fromClip[1] * x + fromClip[5] * y + fromClip[9] + fromClip[13]) / w,
                (fromClip[2] * x + fromClip[6] * y + fromClip[10] + fromClip[14]) / w};
    }
}
