package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.presentation.gen.NrdComposePushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/** Prepares one stable plane for NRD and merges its remodulated output into scene radiance. */
public final class RtNrdComposePipeline {
    public static final int SIGNAL_LINEAR_RGB = 0;
    public static final int SIGNAL_YCOCG = 1;
    /** Fixed scene-linear scale keeps photometric daylight within NRD's FP16 moment range. */
    public static final float RADIANCE_SCALE = 1.0f / 4096.0f;
    private static final String SHADER = "/caustica/shaders/pipelines/nrd_compose/main.comp.spv";
    private final VulkanDeviceContext context;
    private final ShaderObjectCompute shader;

    public record Exchange(GpuImage output, GpuImage diffuseSignal, GpuImage specularSignal,
                           GpuImage normalRoughness, GpuImage viewZ, GpuImage motion,
                           GpuImage disocclusionMix, GpuImage denoisedDiffuse,
                           GpuImage denoisedSpecular, GpuImage stableRadiance) {
        public Exchange {
            java.util.Objects.requireNonNull(output, "output");
            java.util.Objects.requireNonNull(diffuseSignal, "diffuseSignal");
            java.util.Objects.requireNonNull(specularSignal, "specularSignal");
            java.util.Objects.requireNonNull(normalRoughness, "normalRoughness");
            java.util.Objects.requireNonNull(viewZ, "viewZ");
            java.util.Objects.requireNonNull(motion, "motion");
            java.util.Objects.requireNonNull(disocclusionMix, "disocclusionMix");
            java.util.Objects.requireNonNull(denoisedDiffuse, "denoisedDiffuse");
            java.util.Objects.requireNonNull(denoisedSpecular, "denoisedSpecular");
            java.util.Objects.requireNonNull(stableRadiance, "stableRadiance");
        }
    }

    private RtNrdComposePipeline(VulkanDeviceContext context, ShaderObjectCompute shader) {
        this.context = context;
        this.shader = shader;
    }

    public static RtNrdComposePipeline create(VulkanDeviceContext context) {
        return new RtNrdComposePipeline(context, PresentationShaders.load(context, SHADER));
    }

    public void prepare(VkCommandBuffer command, long stablePlaneAddress, long frameAddress,
                        int plane, int signalEncoding, Exchange exchange) {
        dispatch(command, stablePlaneAddress, frameAddress, plane, signalEncoding, 0, exchange);
    }

    public void merge(VkCommandBuffer command, long stablePlaneAddress, long frameAddress,
                      int plane, int signalEncoding, Exchange exchange) {
        dispatch(command, stablePlaneAddress, frameAddress, plane, signalEncoding, 1, exchange);
    }

    private void dispatch(VkCommandBuffer command, long stablePlaneAddress, long frameAddress,
                          int plane, int signalEncoding, int mode, Exchange exchange) {
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(context, command,
                     mode == 0 ? "NRD plane prepare" : "NRD plane merge")) {
            ByteBuffer push = stack.malloc(NrdComposePushData.BYTE_SIZE);
            new NrdComposePushData(stablePlaneAddress, frameAddress, storage(exchange.output()),
                    storage(exchange.diffuseSignal()), storage(exchange.specularSignal()),
                    storage(exchange.normalRoughness()), storage(exchange.viewZ()),
                    storage(exchange.motion()), storage(exchange.disocclusionMix()),
                    storage(exchange.denoisedDiffuse()), storage(exchange.denoisedSpecular()),
                    storage(exchange.stableRadiance()), plane, signalEncoding, mode).write(push);
            shader.dispatch(command, push, (exchange.output().width() + 15) / 16,
                    (exchange.output().height() + 15) / 16, 1);
        }
    }

    private static int storage(GpuImage image) {
        return image.descriptor(GpuImageDescriptorKind.STORAGE).index().value();
    }

    public void destroy() {
        shader.close();
    }

}
