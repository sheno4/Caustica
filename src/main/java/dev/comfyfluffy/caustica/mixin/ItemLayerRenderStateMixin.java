package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.rt.entity.RtEntityCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.TridentSpecialRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public class ItemLayerRenderStateMixin {
    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/special/SpecialModelRenderer;submit(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V"))
    private void caustica$captureSpecialModelGlint(SpecialModelRenderer<?> renderer, Object argument, PoseStack pose,
            SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline, Operation<Void> original) {
        // These vanilla models submit a second copy of their mesh for the depth-equal glint pass.
        if (foil && collector instanceof RtEntityCollector rt && rt.isCapturingHeldItem()
                && (renderer instanceof ShieldSpecialRenderer || renderer instanceof TridentSpecialRenderer)) {
            rt.captureModelGlint(() -> original.call(renderer, argument, pose, collector, light, overlay, false, outline));
            return;
        }
        original.call(renderer, argument, pose, collector, light, overlay, foil, outline);
    }
}
