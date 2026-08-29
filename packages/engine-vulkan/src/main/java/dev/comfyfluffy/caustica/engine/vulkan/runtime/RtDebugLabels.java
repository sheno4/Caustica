package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import dev.comfyfluffy.caustica.spi.vulkan.DebugMarkers;

/** Debug labels for renderer-owned raw Vulkan objects. */
public final class RtDebugLabels {
    private static final String PREFIX = "RT ";
    private static final int VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR = 1000150000;
    private static final int VK_OBJECT_TYPE_MICROMAP_EXT = 1000396000;

    private RtDebugLabels() {}

    public static void name(VulkanDeviceContext ctx, int objectType, long handle, String label) {
        if (ctx == null || handle == 0L || label == null || label.isBlank()) {
            return;
        }
        ctx.backend().debugMarkers().nameObject(objectType, handle, PREFIX + label);
    }

    public static void nameBuffer(VulkanDeviceContext ctx, long handle, String label) {
        name(ctx, VK10.VK_OBJECT_TYPE_BUFFER, handle, label);
    }

    public static void nameImage(VulkanDeviceContext ctx, long handle, String label) {
        name(ctx, VK10.VK_OBJECT_TYPE_IMAGE, handle, label);
    }

    public static void nameImageView(VulkanDeviceContext ctx, long handle, String label) {
        name(ctx, VK10.VK_OBJECT_TYPE_IMAGE_VIEW, handle, label);
    }

    public static void nameAccelerationStructure(VulkanDeviceContext ctx, long handle, String label) {
        name(ctx, VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR, handle, label);
    }

    public static void nameMicromap(VulkanDeviceContext ctx, long handle, String label) {
        name(ctx, VK_OBJECT_TYPE_MICROMAP_EXT, handle, label);
    }

    public static Scope scope(VulkanDeviceContext ctx, VkCommandBuffer cmd, String label) {
        if (ctx == null || cmd == null || label == null || label.isBlank()) {
            return Scope.NOOP;
        }
        DebugMarkers.Scope scope = ctx.backend().debugMarkers().begin(cmd, PREFIX + label);
        return scope::close;
    }

    public interface Scope extends AutoCloseable {
        Scope NOOP = () -> {};

        @Override
        void close();
    }
}
