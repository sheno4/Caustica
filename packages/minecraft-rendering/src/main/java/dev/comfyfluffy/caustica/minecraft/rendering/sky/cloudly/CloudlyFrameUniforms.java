package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import java.nio.ByteBuffer;

/** Host view inputs for original centimetre uniforms; authored cloud and lighting fields remain intact. */
public final class CloudlyFrameUniforms {
    private CloudlyFrameUniforms() { }

    public static void writeView(CloudlyShaderLibrary.ParameterLayout layout, ByteBuffer target,
                                 CloudlyFrameCamera current, CloudlyFrameCamera previous,
                                 int width, int height, float jitterX, float jitterY,
                                 float previousJitterX, float previousJitterY,
                                 float preExposure, float time, float previousTime, int frameNumber) {
        float[] origin = centimeters(current), previousOrigin = centimeters(previous);
        for (String name : new String[]{"ViewOriginHigh", "WorldViewOriginHigh", "PreViewTranslationHigh",
                "PrevWorldCameraOriginHigh", "PrevWorldViewOriginHigh", "PrevPreViewTranslationHigh", "ViewTilePosition"}) {
            layout.putFloats(target, "View." + name, 0, 0, 0);
        }
        for (String name : new String[]{"ViewOriginLow", "WorldViewOriginLow", "RelativeWorldCameraOriginTO", "RelativeWorldViewOriginTO"}) {
            layout.putFloats(target, "View." + name, origin);
        }
        for (String name : new String[]{"PrevWorldCameraOriginLow", "PrevWorldViewOriginLow",
                "PrevRelativeWorldCameraOriginTO", "PrevRelativeWorldViewOriginTO"}) {
            layout.putFloats(target, "View." + name, previousOrigin);
        }
        layout.putFloats(target, "View.PreViewTranslationLow", -origin[0], -origin[1], -origin[2]);
        layout.putFloats(target, "View.RelativePreViewTranslationTO", -origin[0], -origin[1], -origin[2]);
        layout.putFloats(target, "View.PrevPreViewTranslationLow", -previousOrigin[0], -previousOrigin[1], -previousOrigin[2]);
        layout.putFloats(target, "View.RelativePrevPreViewTranslationTO", -previousOrigin[0], -previousOrigin[1], -previousOrigin[2]);
        layout.putFloats(target, "View.ViewForward", current.direction(0, 0));
        layout.putFloats(target, "View.ViewRight", current.sourceRight());
        layout.putFloats(target, "View.ViewUp", current.sourceUp());
        layout.putFloats(target, "View.RelativeWorldToClip", current.worldToClipForSourceUniform());
        layout.putFloats(target, "View.ClipToRelativeWorld", current.worldFromClipForSourceUniform());
        layout.putFloats(target, "View.TranslatedWorldToClip", current.cameraToClipForSourceUniform());
        layout.putFloats(target, "View.ClipToTranslatedWorld", current.cameraFromClipForSourceUniform());
        layout.putFloats(target, "View.TranslatedWorldToView", current.viewFromTranslatedWorldForSourceUniform());
        layout.putFloats(target, "View.PrevClipToRelativeWorld", previous.worldFromClipForSourceUniform());
        layout.putFloats(target, "View.ClipToPrevClip", current.clipToPreviousForSourceUniform(previous));
        layout.putFloats(target, "View.InvDeviceZToWorldZTransform", current.deviceDepthToViewCentimeters());
        layout.putFloats(target, "View.TemporalAAJitter", jitterX, jitterY, previousJitterX, previousJitterY);
        layout.putFloats(target, "View.FieldOfViewWideAngles", current.horizontalFieldOfView(), current.verticalFieldOfView());
        layout.putFloats(target, "View.PrevFieldOfViewWideAngles", previous.horizontalFieldOfView(), previous.verticalFieldOfView());
        layout.putFloats(target, "View.ViewSizeAndInvSize", width, height, 1f / width, 1f / height);
        layout.putFloats(target, "View.BufferSizeAndInvSize", width, height, 1f / width, 1f / height);
        layout.putFloats(target, "View.ViewRectMin", 0, 0, 0, 0);
        layout.putInts(target, "View.ViewRectMinAndSize", 0, 0, width, height);
        layout.putFloats(target, "View.ScreenPositionScaleBias", 0.5f, -0.5f, 0.5f, 0.5f);
        layout.putFloats(target, "View.PreExposure", preExposure);
        layout.putFloats(target, "View.OneOverPreExposure", 1f / preExposure);
        layout.putFloats(target, "View.GameTime", time);
        layout.putFloats(target, "View.PrevFrameGameTime", previousTime);
        layout.putInts(target, "View.FrameNumber", frameNumber);
    }

    private static float[] centimeters(CloudlyFrameCamera camera) {
        float[] origin = camera.sourceOriginMeters();
        for (int index = 0; index < origin.length; index++) origin[index] *= 100;
        return origin;
    }
}
