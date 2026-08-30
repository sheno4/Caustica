package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the deferred graphics command buffer used by Minecraft's current submission. */
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
    @Invoker("commandBuffer")
    VkCommandBuffer caustica$commandBuffer();
}
