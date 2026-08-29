package dev.comfyfluffy.caustica.renderer.presentation;


import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.renderer.presentation.gen.ExposureHistPushData;
import dev.comfyfluffy.caustica.renderer.presentation.gen.ExposureResolvePushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/** Descriptor-heap shader objects for histogram auto exposure. */
final class RtExposurePipeline {
    private static final String ROOT = "/caustica/shaders/pipelines/";
    private final VulkanDeviceContext context;
    private final ShaderObjectCompute histogramShader;
    private final ShaderObjectCompute resolveShader;

    private RtExposurePipeline(VulkanDeviceContext context, ShaderObjectCompute histogramShader,
                               ShaderObjectCompute resolveShader) {
        this.context = context;
        this.histogramShader = histogramShader;
        this.resolveShader = resolveShader;
    }

    static RtExposurePipeline create(VulkanDeviceContext context) {
        ShaderObjectCompute histogram = load(context, "exposure_hist/main.comp.spv");
        try {
            return new RtExposurePipeline(context, histogram,
                    load(context, "exposure_resolve/main.comp.spv"));
        } catch (RuntimeException | Error failure) {
            histogram.close();
            throw failure;
        }
    }

    void dispatchHistogram(VkCommandBuffer command, GpuImage color, GpuImage depth, GpuImage albedo,
                           GpuBuffer histogram, RtExposure.AutoConfig config) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, command, "exposure histogram")) {
            ByteBuffer push = stack.malloc(ExposureHistPushData.BYTE_SIZE);
            new ExposureHistPushData(storage(color), storage(depth), storage(albedo),
                    histogram.deviceAddress().value(), config.stride(), config.centerWeightSigma(),
                    config.centerWeightFloor()).write(push);
            int sampleWidth = (color.width() + config.stride() - 1) / config.stride();
            int sampleHeight = (color.height() + config.stride() - 1) / config.stride();
            histogramShader.dispatch(command, push, (sampleWidth + 15) / 16,
                    (sampleHeight + 15) / 16, 1);
        }
    }

    void dispatchResolve(VkCommandBuffer command, GpuBuffer histogram, GpuImage exposure,
                         GpuBuffer state, RtExposure.AutoConfig config, float frameTimeSeconds) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, command, "exposure resolve")) {
            RtExposure.ExposureCurve curve = config.curve();
            ByteBuffer push = stack.malloc(ExposureResolvePushData.BYTE_SIZE);
            new ExposureResolvePushData(histogram.deviceAddress().value(), state.deviceAddress().value(), storage(exposure),
                    config.key(), config.minEv(), config.maxEv(), config.adaptDarken(), config.adaptBrighten(),
                    frameTimeSeconds, config.evBias(), config.lowPercentile(), config.highPercentile(),
                    config.skyWeightCap(), curve.scene0(), curve.compensation0(), curve.scene1(),
                    curve.compensation1(), curve.scene2(), curve.compensation2(), curve.scene3(),
                    curve.compensation3(), config.emissiveWeightCap(), config.evOffset(), config.preExposure(),
                    config.resetSequence()).write(push);
            resolveShader.dispatch(command, push, 1, 1, 1);
        }
    }

    private static int storage(GpuImage image) {
        return image.descriptor(GpuImageDescriptorKind.STORAGE).index().value();
    }

    private static ShaderObjectCompute load(VulkanDeviceContext context, String name) {
        String resource = ROOT + name;
        try (InputStream input = RtExposurePipeline.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + resource);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try {
                spirv.put(bytes).flip();
                return ShaderObjectCompute.create(context, spirv, "main");
            } finally {
                MemoryUtil.memFree(spirv);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    void destroy() {
        resolveShader.close();
        histogramShader.close();
    }
}
