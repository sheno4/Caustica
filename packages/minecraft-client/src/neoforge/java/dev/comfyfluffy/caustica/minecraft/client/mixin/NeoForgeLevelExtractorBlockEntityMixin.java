package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips vanilla block-entity extraction while RT owns the world. The enclosing loop still runs
 * to prune removed entries from the level's globally rendered block-entity set.
 *
 * <p>NeoForge patches vanilla to thread a {@code Frustum} through block-entity extraction. It keeps the
 * unpatched {@code extractVisibleBlockEntities} and {@code tryExtractRenderState} as overloads, but the live
 * call moves into the {@code Frustum}-carrying pair — so both descriptors are pinned here, and a name-only
 * selector would bind the overload nothing calls.</p>
 */
@Mixin(LevelExtractor.class)
public class NeoForgeLevelExtractorBlockEntityMixin {
    @WrapOperation(method = "extractVisibleBlockEntities(Lnet/minecraft/client/Camera;F"
            + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
            + "Lnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;"
                            + "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
                            + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z"
                            + "Lnet/minecraft/client/renderer/culling/Frustum;)"
                            + "Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"))
    private BlockEntityRenderState caustica$skipVanillaBlockEntityExtraction(
            BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTicks,
            ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean isGloballyRendered, Frustum frustum,
            Operation<BlockEntityRenderState> original) {
        return CausticaClientComposition.current().renderController().rtOwnsWorldRendering()
                ? null
                : original.call(dispatcher, blockEntity, partialTicks, breakProgress, isGloballyRendered,
                        frustum);
    }
}
