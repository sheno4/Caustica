package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Optional diagnostic that completes each host graphics submission before recording the next. */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanSerialFramesMixin {
    @Unique
    private static final boolean caustica$serialFrames = Boolean.getBoolean("caustica.debug.serial-frames");

    @ModifyArg(method = "submit()V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z"), index = 0)
    private long caustica$waitForSubmittedFrame(long submitIndex) {
        // The host has submitted its final timeline signal and incremented its current index.
        try (var observation = MinecraftHostTelemetry.callback(MinecraftHostTelemetry.Callback.SERIAL_SUBMIT)) {
            return caustica$serialFrames ? submitIndex + 1L : submitIndex;
        }
    }
}
