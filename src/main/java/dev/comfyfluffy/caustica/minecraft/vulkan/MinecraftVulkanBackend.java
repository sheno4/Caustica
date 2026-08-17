package dev.comfyfluffy.caustica.minecraft.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import dev.comfyfluffy.caustica.mixin.CommandEncoderAccessor;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.spi.vulkan.DebugMarkers;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanQueueRef;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/** Adapts Blaze3D's deferred Vulkan encoder and device wrappers to renderer-owned interfaces. */
public final class MinecraftVulkanBackend implements VulkanRendererBackend {
    private final VulkanDevice device;
    private final VulkanQueueRef graphicsQueue;
    private final VulkanQueueRef computeQueue;
    private final DebugMarkers debugMarkers;

    private MinecraftVulkanBackend(VulkanDevice device) {
        this.device = device;
        VulkanQueue graphics = device.graphicsQueue();
        VulkanQueue compute = new VulkanQueue(device, RtDeviceBringup.computeQueueFamilyIndex(),
                RtDeviceBringup.computeQueueIndex());
        this.graphicsQueue = new VulkanQueueRef(graphics.vkQueue(), graphics.queueFamilyIndex());
        this.computeQueue = new VulkanQueueRef(compute.vkQueue(), compute.queueFamilyIndex());
        this.debugMarkers = new MinecraftDebugMarkers(device);
    }

    public static void installCurrent() {
        if (GpuContext.backendOrNull() != null) {
            return;
        }
        Object backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend();
        if (backend instanceof VulkanDevice device && RtDeviceBringup.computeQueueReserved()) {
            GpuContext.installBackend(new MinecraftVulkanBackend(device));
        }
    }

    public static GraphicsSubmission wrap(VulkanCommandEncoder encoder) {
        return new Submission(encoder);
    }

    @Override
    public VkDevice device() {
        return device.vkDevice();
    }

    @Override
    public VulkanQueueRef graphicsQueue() {
        return graphicsQueue;
    }

    @Override
    public VulkanQueueRef computeQueue() {
        return computeQueue;
    }

    @Override
    public GraphicsSubmission createGraphicsSubmission() {
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice()
                .createCommandEncoder()).caustica$getBackend();
        return new Submission(encoder);
    }

    @Override
    public void assertRenderThread() {
        RenderSystem.assertOnRenderThread();
    }

    @Override
    public DebugMarkers debugMarkers() {
        return debugMarkers;
    }

    @Override
    public boolean rayTracingProvisioned() {
        return RtDeviceBringup.rtRequested();
    }

    private record Submission(VulkanCommandEncoder encoder) implements GraphicsSubmission {
        @Override
        public VkCommandBuffer beginTransientCommandBuffer() {
            return encoder.allocateAndBeginTransientCommandBuffer();
        }

        @Override
        public void waitSemaphore(long semaphore, long value, long stageMask) {
            encoder.waitSemaphore(semaphore, value, stageMask);
        }

        @Override
        public void execute(VkCommandBuffer commandBuffer) {
            encoder.execute(commandBuffer);
        }

        @Override
        public void signalSemaphore(long semaphore, long value, long stageMask) {
            encoder.signalSemaphore(semaphore, value, stageMask);
        }
    }

    private record MinecraftDebugMarkers(VulkanDevice device) implements DebugMarkers {
        @Override
        public void nameObject(int objectType, long handle, String label) {
            device.instance().debug().setObjectName(device.vkDevice(), objectType, handle, label);
        }

        @Override
        public Scope begin(VkCommandBuffer commandBuffer, String label) {
            device.instance().debug().beginDebugGroup(commandBuffer, () -> label);
            return () -> device.instance().debug().endDebugGroup(commandBuffer);
        }
    }
}
