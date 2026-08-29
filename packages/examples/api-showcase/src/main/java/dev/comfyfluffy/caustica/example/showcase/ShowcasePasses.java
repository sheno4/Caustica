package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassId;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

final class ShowcasePasses {
    static final PassId BLOOM = PassId.of("caustica", "bloom");
    static final PassId POST_EFFECT = PassId.of("caustica_showcase", "colour_grade");
    static final PassId UI = PassId.of("caustica_showcase", "world_marker");
    static final PassId WORLD_OVERLAY = PassId.of("caustica", "world_overlay");

    private ShowcasePasses() { }

    static Pass<PassFrame> worldResource(GpuDevice gpu) {
        return new Pass<>() {
            @Override
            public void record(PassFrame frame) {
                if (runtimeGpuRecordingEnabled()) {
                    var view = frame.view();
                    double shaderTime = frame.timeSeconds();
                    double metresPerUnit = frame.metersPerSceneUnit();
                    frame.gpuUse().whenComplete(() -> { });
                    gpu.retireAfterUse(() -> { });
                    throw missingCommands(view.entryScene(), shaderTime, metresPerUnit,
                            frame.renderWidth(), frame.renderHeight());
                }
            }

            @Override
            public void close() { }
        };
    }

    static Pass<PostEffectFrame> postEffect(GpuDevice gpu) {
        return new Pass<>() {
            @Override
            public void record(PostEffectFrame frame) {
                if (runtimeGpuRecordingEnabled()) {
                    int input = frame.sceneColor().descriptor(GpuImageDescriptorKind.SAMPLED).index().value();
                    int exposure = frame.exposureImage().descriptor(GpuImageDescriptorKind.SAMPLED).index().value();
                    int output = frame.acquireSceneColorOutput()
                            .descriptor(GpuImageDescriptorKind.STORAGE).index().value();
                    throw missingCommands(input, exposure, output);
                }
            }

            @Override
            public void close() { }
        };
    }

    static Pass<UiFrame> ui(GpuDevice gpu) {
        return new Pass<>() {
            @Override
            public void record(UiFrame frame) {
                if (runtimeGpuRecordingEnabled()) {
                    int layer = frame.layer().descriptor(GpuImageDescriptorKind.STORAGE).index().value();
                    GpuAccelerationStructureDescriptor tlas = frame.entrySceneTlasDescriptor();
                    float[] worldViewProjection = frame.worldViewProjection();
                    var view = frame.view();
                    throw missingCommands(layer, tlas.index().value(), worldViewProjection.length,
                            view.camera().clipFromView().length);
                }
            }

            @Override
            public void close() { }
        };
    }

    /** Shape of raw descriptor writes needed by an extension-owned texture table. */
    static void writeDescriptors(GpuDescriptorWriter writer,
                                 GpuDescriptorRange<GpuDescriptorIndex.Resource> resources,
                                 GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplers,
                                 VkResourceDescriptorInfoEXT resource,
                                 VkSamplerCreateInfo sampler,
                                 long accelerationStructure) {
        writer.writeResource(resources, 0, resource);
        writer.writeAccelerationStructure(resources, 1, accelerationStructure);
        writer.writeSampler(samplers, 0, sampler);
    }

    static boolean runtimeGpuRecordingEnabled() {
        return false;
    }

    private static UnsupportedOperationException missingCommands(Object... facts) {
        return new UnsupportedOperationException(
                "compile-only API probe has no Vulkan pipeline or valid recording path (facts="
                        + facts.length + ")");
    }
}
