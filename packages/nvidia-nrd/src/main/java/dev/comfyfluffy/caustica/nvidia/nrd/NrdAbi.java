package dev.comfyfluffy.caustica.nvidia.nrd;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserCommonSettings;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserFrame;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserImage;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserInputs;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class NrdAbi {
    static final long CREATE_SIZE = 48;
    static final long COMMON_SIZE = 308;
    static final long IMAGE_SIZE = 16;
    static final long RESOURCES_SIZE = IMAGE_SIZE * 9;

    private NrdAbi() {}

    static void writeCreate(MemorySegment target, NrdDevice device, NrdMethod method, int width, int height) {
        target.set(ValueLayout.JAVA_LONG, 0, device.instance());
        target.set(ValueLayout.JAVA_LONG, 8, device.physicalDevice());
        target.set(ValueLayout.JAVA_LONG, 16, device.device());
        target.set(ValueLayout.JAVA_INT, 24, device.graphicsQueueFamily());
        target.set(ValueLayout.JAVA_INT, 28, device.queuedFrames());
        target.set(ValueLayout.JAVA_INT, 32, width);
        target.set(ValueLayout.JAVA_INT, 36, height);
        target.set(ValueLayout.JAVA_INT, 40, method.ordinal());
        target.set(ValueLayout.JAVA_INT, 44, 0);
    }

    static void writeCommon(MemorySegment target, DenoiserFrame frame) {
        DenoiserCommonSettings value = frame.common();
        putMatrix(target, 0, value.worldToView());
        putMatrix(target, 64, value.worldToViewPrevious());
        putMatrix(target, 128, value.viewToClip());
        putMatrix(target, 192, value.viewToClipPrevious());
        float[] scalars = {value.jitterX(), value.jitterY(), value.previousJitterX(), value.previousJitterY(),
                value.motionScaleX(), value.motionScaleY(), value.motionScaleZ(), value.denoisingRange(),
                value.disocclusionThreshold(), value.alternateDisocclusionThreshold(), value.frameTimeMilliseconds()};
        for (int i = 0; i < scalars.length; i++) target.set(ValueLayout.JAVA_FLOAT, 256L + i * 4L, scalars[i]);
        target.set(ValueLayout.JAVA_INT, 300, value.frameIndex());
        int flags = (value.motionInWorldSpace() ? 1 : 0)
                | (frame.inputs().disocclusionThresholdMix().isPresent() ? 2 : 0)
                | (frame.inputs().validationOutput().isPresent() ? 4 : 0)
                | (value.reset().ordinal() << 3);
        target.set(ValueLayout.JAVA_INT, 304, flags);
    }

    static void writeResources(MemorySegment target, DenoiserInputs inputs) {
        DenoiserImage[] images = {inputs.diffuseRadianceHitDistance(), inputs.specularRadianceHitDistance(),
                inputs.normalRoughness(), inputs.viewZ(), inputs.motion(),
                inputs.denoisedDiffuseRadianceHitDistance(), inputs.denoisedSpecularRadianceHitDistance(),
                inputs.disocclusionThresholdMix().orElse(null), inputs.validationOutput().orElse(null)};
        for (int i = 0; i < images.length; i++) writeImage(target, i * IMAGE_SIZE, images[i]);
    }

    private static void putMatrix(MemorySegment target, long offset, float[] matrix) {
        MemorySegment.copy(matrix, 0, target, ValueLayout.JAVA_FLOAT, offset, 16);
    }

    private static void writeImage(MemorySegment target, long offset, DenoiserImage image) {
        if (image == null) {
            target.asSlice(offset, IMAGE_SIZE).fill((byte) 0);
            return;
        }
        target.set(ValueLayout.JAVA_LONG, offset, image.image());
        target.set(ValueLayout.JAVA_INT, offset + 8, image.format());
        target.set(ValueLayout.JAVA_INT, offset + 12, image.layout());
    }
}
