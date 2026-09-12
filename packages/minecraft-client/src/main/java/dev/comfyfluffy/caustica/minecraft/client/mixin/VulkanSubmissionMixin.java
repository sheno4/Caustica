package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Measures host submission setup and retirement separately from its explicit GPU-completion await. */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanSubmissionMixin {
    @Unique
    private MinecraftHostTelemetry.SubmissionScope caustica$submission = MinecraftHostTelemetry.SubmissionScope.NOOP;

    @WrapMethod(method = "submit()V")
    private void caustica$measureHostSubmit(Operation<Void> original) {
        var previous = caustica$submission;
        try (var observation = MinecraftHostTelemetry.submission()) {
            caustica$submission = observation;
            original.call();
            observation.completed();
        } finally {
            caustica$submission = previous;
        }
    }

    @WrapOperation(method = "submit()V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z"))
    private boolean caustica$excludeCompletionAwait(VulkanCommandEncoder encoder, long index, long timeout,
                                                   Operation<Boolean> original) {
        var observation = caustica$submission;
        observation.beforeAwait();
        try {
            return original.call(encoder, index, timeout);
        } finally {
            observation.afterAwait();
        }
    }
}
