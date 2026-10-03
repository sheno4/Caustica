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

    private float[] unproject(float x, float y) {
        float w = fromClip[3] * x + fromClip[7] * y + fromClip[11] + fromClip[15];
        return new float[]{(fromClip[0] * x + fromClip[4] * y + fromClip[8] + fromClip[12]) / w,
                (fromClip[1] * x + fromClip[5] * y + fromClip[9] + fromClip[13]) / w,
                (fromClip[2] * x + fromClip[6] * y + fromClip[10] + fromClip[14]) / w};
    }
}
