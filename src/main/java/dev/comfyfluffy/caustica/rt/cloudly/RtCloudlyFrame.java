package dev.comfyfluffy.caustica.rt.cloudly;

import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

/** One borrowed native recording scope, retained through the host's exact graphics completion. */
public final class RtCloudlyFrame implements PostEffectFrame, AutoCloseable {
    private final VkCommandBuffer commands;
    private final long index;
    private final double time;
    private final SceneView view;
    private final float[] inverseClip;
    private final float[] jitter;
    private final float preExposure;
    private final GpuImage color, depth, output;
    private final ResourceOwner outputOwner;
    private final int renderWidth, renderHeight;
    private final List<Runnable> accepted = new ArrayList<>();
    private final List<Runnable> retired = new ArrayList<>();
    private boolean acquired, finished;

    RtCloudlyFrame(VkCommandBuffer commands, long index, double time, Camera camera,
                   float[] inverseClip, float jitterX, float jitterY, float preExposure,
                   GpuImage color, GpuImage depth, GpuImage output, ResourceOwner outputOwner,
                   int renderWidth, int renderHeight) {
        this.commands = commands;
        this.index = index;
        this.time = time;
        this.view = new SceneView(camera);
        this.inverseClip = inverseClip.clone();
        this.jitter = new float[]{jitterX, jitterY};
        this.preExposure = preExposure;
        this.color = color;
        this.depth = depth;
        this.output = output;
        this.outputOwner = outputOwner;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
    }

    @Override public VkCommandBuffer commandBuffer() { return commands; }
    @Override public long frameIndex() { return index; }
    @Override public SceneView view() { return view; }
    @Override public double timeSeconds() { return time; }
    @Override public double metersPerSceneUnit() { return RtCloudlyRenderer.METERS_PER_BLOCK; }
    @Override public int renderWidth() { return renderWidth; }
    @Override public int renderHeight() { return renderHeight; }
    @Override public GpuImage sceneColor() { return color; }
    @Override public GpuImage primaryDepth() { return depth; }
    @Override public GpuImage depth() { return depth; }
    @Override public float[] cameraRelativeFromClip() { return inverseClip.clone(); }
    @Override public float[] traceJitter() { return jitter.clone(); }
    @Override public float preExposure() { return preExposure; }
    @Override public boolean spatialMediumActive() { return false; }
    @Override public GpuImage exposureImage() { throw new UnsupportedOperationException("Sky composition precedes exposure"); }
    @Override public GpuAccelerationStructureDescriptor entrySceneTlasDescriptor() {
        throw new UnsupportedOperationException("Native sky does not trace the host acceleration structure");
    }
    @Override public float[] cameraTlasPosition() {
        throw new UnsupportedOperationException("Native sky reconstructs camera-relative positions from depth");
    }
    @Override public void traceVisibility(VulkanDeviceAddress rays, VulkanDeviceAddress results, int width, int height) {
        throw new UnsupportedOperationException("Native sky owns its cloud visibility pipeline");
    }
    @Override public void sampleVolumeLighting(VulkanDeviceAddress samples, VulkanDeviceAddress results, int width, int height) {
        throw new UnsupportedOperationException("Native sky owns its volume lighting pipeline");
    }

    @Override public GpuImage acquireSceneColorOutput() {
        if (!acquired) { retain(outputOwner); acquired = true; }
        return output;
    }

    public boolean outputAcquired() { return acquired; }

    @Override public void retain(ResourceOwner owner) {
        ResourceOwner claim = owner.retain();
        retired.add(claim::close);
    }

    @Override public void whenSubmitted(Runnable callback) { accepted.add(callback); }
    @Override public void whenRetired(Runnable callback) { retired.add(callback); }

    /** Call only after the containing command buffer has been accepted by the encoder. */
    public void submitted(RtGpuExecutor executor, RtGpuExecutor.GraphicsUse graphicsUse) {
        if (finished) throw new IllegalStateException("Cloudly frame already finished");
        finished = true;
        List<Runnable> completion = List.copyOf(retired);
        retired.clear();
        executor.retireAfterGraphics(graphicsUse, () -> runAll(completion));
        runAll(accepted);
        accepted.clear();
    }

    /** Recording was abandoned and no submitted commands may reference these claims. */
    @Override public void close() {
        if (finished) return;
        finished = true;
        accepted.clear();
        runAll(retired);
        retired.clear();
    }

    private static void runAll(List<Runnable> callbacks) {
        Throwable failure = null;
        for (Runnable callback : callbacks) {
            try { callback.run(); }
            catch (Throwable error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw new IllegalStateException("Cloudly frame callback failed", failure);
    }
}
