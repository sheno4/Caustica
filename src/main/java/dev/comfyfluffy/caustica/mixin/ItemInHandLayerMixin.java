package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.rt.entity.RtEntityCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;

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
}
