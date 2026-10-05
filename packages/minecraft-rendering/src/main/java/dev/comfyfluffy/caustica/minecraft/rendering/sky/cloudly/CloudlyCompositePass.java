package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.pass.*;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.gen.CloudlyCompositePushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteOrder;
import java.util.function.Supplier;

/** Composes separately traced scattering after world reconstruction without changing its surface guides. */
public final class CloudlyCompositePass implements Pass<PostEffectFrame> {
    public static final PassId ID = new PassId("caustica", "cloudly_composite");
    private final Supplier<CloudlyCloudPass> clouds;
    private final ShaderObjectCompute shader;
    private final ResourceOwner owner;

    public CloudlyCompositePass(PostEffectSetup setup, ResourceFactory resources, Supplier<CloudlyCloudPass> clouds) {
        this.clouds = clouds;
        shader = ShaderObjectCompute.load(setup.gpu(), CloudlyCompositePass.class,
                "/caustica/shaders/pipelines/cloudly/composite.comp.spv");
        try { owner = resources.create(shader::close); }
        catch (RuntimeException | Error failure) { shader.close(); throw failure; }
    }

    @Override public void record(PostEffectFrame frame) {
        CloudlyCloudPass pass = clouds.get();
        if (pass == null) return;
        var layer = pass.composedLayer(frame);
        if (layer == null) return;
        frame.retain(owner);
        var scene = frame.sceneColor();
        var output = frame.acquireSceneColorOutput();
        float[] jitter = frame.traceJitter();
        try (var stack = MemoryStack.stackPush()) {
            var push = stack.malloc(CloudlyCompositePushData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            new CloudlyCompositePushData(output.descriptor(GpuImageDescriptorKind.STORAGE).index().value(),
                    scene.descriptor(GpuImageDescriptorKind.SAMPLED).index().value(), layer.image().sampledIndex().value(),
                    frame.primaryDepth().descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                    new CloudlyCompositePushData.Float4(jitter[0], jitter[1], layer.image().width(), layer.image().height())).write(push);
            shader.dispatch(frame.commandBuffer(), push, (output.width() + 7) / 8, (output.height() + 7) / 8, 1);
        }
    }

    @Override public void close() { owner.close(); }
}
