package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.renderer.presentation.gen.DebugPresentPushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/** Presents guide-buffer diagnostics after exposure and display mapping. */
public final class RtDebugPresentPipeline {
    private static final String SHADER = "/caustica/shaders/pipelines/debug_present/main.comp.spv";
    private final VulkanDeviceContext context;
    private final ShaderObjectCompute shader;

    private RtDebugPresentPipeline(VulkanDeviceContext context, ShaderObjectCompute shader) {
        this.context = context;
        this.shader = shader;
    }

    public static RtDebugPresentPipeline create(VulkanDeviceContext context) {
        return new RtDebugPresentPipeline(context,
                ShaderObjectCompute.load(context, RtDebugPresentPipeline.class, SHADER));
    }

    public void dispatch(VkCommandBuffer command, GpuImage output, GpuImage normal, GpuImage albedo,
                         GpuImage depth, GpuImage motion, GpuImage specAlbedo, GpuImage specMotion,
                         GpuImage scene, GpuImage exposure, GpuImage traceRadiance,
                         GpuImage stablePlaneMetadata,
                         GpuBuffer exposureState, int debugView,
                         float centerWeightSigma, float centerWeightFloor) {
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(context, command, "debug present")) {
            ByteBuffer push = stack.malloc(DebugPresentPushData.BYTE_SIZE);
            new DebugPresentPushData(storage(output), storage(normal), storage(albedo), storage(depth),
                    storage(motion), storage(specAlbedo), storage(specMotion), storage(scene),
                    storage(exposure), storage(traceRadiance), storage(stablePlaneMetadata),
                    exposureState.deviceAddress().value(), debugView,
                    centerWeightSigma, centerWeightFloor).write(push);
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
