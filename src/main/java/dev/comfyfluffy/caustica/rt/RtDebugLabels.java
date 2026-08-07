package dev.comfyfluffy.caustica.rt;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Thin wrapper over Minecraft's VK_EXT_debug_utils integration for RT-owned raw Vulkan objects. */
public final class RtDebugLabels {
    private static final String PREFIX = "RT ";
    private static final int VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR = 1000150000;
    private static final int VK_OBJECT_TYPE_MICROMAP_EXT = 1000396000;

    private RtDebugLabels() {}

    public static void name(GpuContext ctx, int objectType, long handle, String label) {
        if (ctx == null || handle == 0L || label == null || label.isBlank()) {
            return;
        }
        ctx.device().instance().debug().setObjectName(ctx.vk(), objectType, handle, PREFIX + label);
    }

    public static void nameBuffer(GpuContext ctx, long handle, String label) {
        name(ctx, VK10.VK_OBJECT_TYPE_BUFFER, handle, label);
    }

    public static void nameImage(GpuContext ctx, long handle, String label) {
        name(ctx, VK10.VK_OBJECT_TYPE_IMAGE, handle, label);
    }

    public static void nameImageView(GpuContext ctx, long handle, String label) {
        name(ctx, VK10.VK_OBJECT_TYPE_IMAGE_VIEW, handle, label);
    }

    public static void nameAccelerationStructure(GpuContext ctx, long handle, String label) {
        name(ctx, VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR, handle, label);
    }

    public static void nameMicromap(GpuContext ctx, long handle, String label) {
        name(ctx, VK_OBJECT_TYPE_MICROMAP_EXT, handle, label);
    }

    public static Scope scope(GpuContext ctx, VkCommandBuffer cmd, String label) {
        if (ctx == null || cmd == null || label == null || label.isBlank()) {
            return Scope.NOOP;
        }
        VulkanDiagnostics.breadcrumb("record " + label + " cmd=0x" + Long.toUnsignedString(cmd.address(), 16));
        ctx.device().instance().debug().beginDebugGroup(cmd, () -> PREFIX + label);
        return () -> ctx.device().instance().debug().endDebugGroup(cmd);
    }

    public interface Scope extends AutoCloseable {
        Scope NOOP = () -> {};

        @Override
        void close();
    }
}
