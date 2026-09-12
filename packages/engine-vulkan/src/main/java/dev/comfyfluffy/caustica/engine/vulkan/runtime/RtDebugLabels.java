package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import dev.comfyfluffy.caustica.spi.vulkan.DebugMarkers;
import dev.comfyfluffy.caustica.engine.vulkan.GpuDiagnosticCheckpoints;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR;

/** Debug labels for renderer-owned raw Vulkan objects. */
public final class RtDebugLabels {
    private static final String PREFIX = "RT ";

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

    public static DebugMarkers.Scope scope(VulkanDeviceContext ctx, VkCommandBuffer cmd, String label) {
        if (ctx == null || cmd == null || label == null || label.isBlank()) {
            return DebugMarkers.Scope.NOOP;
        }
        String debugLabel = PREFIX + label;
        var scope = ctx.backend().debugMarkers().begin(cmd, debugLabel);
        if (GpuDiagnosticCheckpoints.ENABLED) return GpuDiagnosticCheckpoints.begin(cmd, debugLabel, scope);
        return scope;
    }
}
