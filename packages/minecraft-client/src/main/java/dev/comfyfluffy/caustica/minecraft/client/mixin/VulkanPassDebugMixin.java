package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftDebugService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes host attachment identities without changing render-pass recording. */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanPassDebugMixin {
    @Inject(method = "createRenderPass", at = @At("HEAD"))
    private void caustica$tracePass(RenderPassDescriptor descriptor,
                                   CallbackInfoReturnable<RenderPassBackend> callback) {
        MinecraftDebugService.renderPass(descriptor);
    }
}
