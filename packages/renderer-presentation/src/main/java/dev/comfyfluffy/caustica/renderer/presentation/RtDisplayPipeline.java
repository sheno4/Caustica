package dev.comfyfluffy.caustica.renderer.presentation;


import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.gen.DisplayPushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/** Maps the display-res scene-linear ACEScg image to sRGB SDR and optional PQ/BT.2020 HDR. */
public final class RtDisplayPipeline {
    private static final String SHADER = "/caustica/shaders/pipelines/display/main.comp.spv";
    private final VulkanDeviceContext context;
    private final ShaderObjectCompute shader;

    private RtDisplayPipeline(VulkanDeviceContext context, ShaderObjectCompute shader) {
        this.context = context;
        this.shader = shader;
    }

    public static RtDisplayPipeline create(VulkanDeviceContext context) {
        try (InputStream input = RtDisplayPipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try {
                spirv.put(bytes).flip();
                return new RtDisplayPipeline(context, ShaderObjectCompute.create(context, spirv, "main"));
            } finally {
                MemoryUtil.memFree(spirv);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public void dispatch(VkCommandBuffer command, GpuImage output, GpuImage scene, GpuImage exposure,
                         GpuImage hdrOutput, RtToneLut toneLut, RtToneLut hdrToneLut,
                         RtToneLut lookLut, boolean hdrEnabled, float gamma, float hdrPeakNits,
                         boolean lookEnabled) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, command, "display compute")) {
            ByteBuffer push = stack.malloc(DisplayPushData.BYTE_SIZE);
            new DisplayPushData(storage(output), storage(scene), storage(exposure), storage(hdrOutput),
                    toneLut.sampledIndex().value(), toneLut.samplerIndex().value(),
                    hdrToneLut.sampledIndex().value(), hdrToneLut.samplerIndex().value(),
                    lookLut.sampledIndex().value(), lookLut.samplerIndex().value(),
                    hdrEnabled ? 1 : 0, toneLut.size, gamma, hdrPeakNits,
                    lookEnabled ? 1 : 0, lookLut.size).write(push);
            shader.dispatch(command, push, (output.width() + 15) / 16, (output.height() + 15) / 16, 1);
        }
    }

    private static int storage(GpuImage image) {
        return image.descriptor(GpuImageDescriptorKind.STORAGE).index().value();
    }

    public void destroy() {
        shader.close();
    }
}
