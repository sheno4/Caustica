package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.renderer.presentation.gen.DisplayPushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

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
        return new RtDisplayPipeline(context, PresentationShaders.load(context, SHADER));
    }

    public void dispatch(VkCommandBuffer command, GpuImage output, GpuImage scene, GpuImage exposure,
                         GpuImage hdrOutput, RtToneLut toneLut, RtToneLut hdrToneLut,
                         RtToneLut lookLut, boolean hdrEnabled, float gamma, float hdrPeakNits,
                         boolean lookEnabled) {
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(context, command, "display compute")) {
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
