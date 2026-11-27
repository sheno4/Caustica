package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.rt.entity.RtEntityCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
    @WrapMethod(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;FF)V")
    private void caustica$captureCameraOwnerHeldItems(PoseStack pose, SubmitNodeCollector collector, int light,
            ArmedEntityRenderState state, float yRot, float xRot, Operation<Void> original) {
        if (collector instanceof RtEntityCollector rt && rt.isCapturingVrCameraOwner()) {
            rt.captureCameraOwnerHeldItems(() -> original.call(pose, collector, light, state, yRot, xRot));
            return;
        }
        original.call(pose, collector, light, state, yRot, xRot);
    }

    @WrapOperation(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;FF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/ItemInHandLayer;submitArmWithItem(Lnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void caustica$captureHeldItemMaterial(ItemInHandLayer<?, ?> layer,
            ArmedEntityRenderState state, ItemStackRenderState renderItem,
            ItemStack item, HumanoidArm arm, PoseStack pose, SubmitNodeCollector collector, int light,
            Operation<Void> original) {
        if (collector instanceof RtEntityCollector rt && rt.isCapturingVrCameraOwner()) {
            // Vivecraft resolves the avatar's main arm and item sides together, including reverse hands.
            int slot = arm == state.mainArm ? RtEntityCollector.MAIN_HAND_ITEM_SLOT : RtEntityCollector.OFF_HAND_ITEM_SLOT;
            rt.captureCameraOwnerHeldItem(item, slot,
                    () -> original.call(layer, state, renderItem, item, arm, pose, collector, light));
            return;
        }
        original.call(layer, state, renderItem, item, arm, pose, collector, light);
    }
}
