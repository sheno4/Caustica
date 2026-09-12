package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandPool;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A failed native reset must stop before the host rewinds its command-buffer allocator. */
@Mixin(VulkanCommandPool.class)
public abstract class VulkanCommandPoolResultMixin {
    @Shadow @Final private VulkanDevice device;

    @Redirect(method = "reset()V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkResetCommandPool(Lorg/lwjgl/vulkan/VkDevice;JI)I"))
    private int caustica$checkReset(VkDevice vk, long pool, int flags) {
        int result = VK12.vkResetCommandPool(vk, pool, flags);
        GpuCrashHistory.record(GpuCrashHistory.Event.HOST_RESET, pool, 0, 0, result);
        VulkanUtils.crashIfFailure(device, result, "vkResetCommandPool(host)");
        return result;
    }
}
