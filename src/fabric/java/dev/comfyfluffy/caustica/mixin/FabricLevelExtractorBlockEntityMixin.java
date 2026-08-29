package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skip vanilla's block-entity render-state build while RT owns the world, for the same reason as
 * {@code LevelExtractorMixin.caustica$skipVanillaEntityExtraction}: {@code RtEntities} drives
 * {@code BlockEntityRenderDispatcher} itself.
 *
 * <p>Wrapped at the extraction call rather than cancelled at the method head because the same method owns
 * the only prune of {@code ClientLevel.getGloballyRenderedBlockEntities()} — its loop is what drops removed
 * block entities from that set. Both call sites already treat a null state as "nothing to render", so the
 * walk keeps running and only the per-block-entity work disappears.</p>
 *
 * <p>Loader-specific because NeoForge patches both signatures to carry a {@code Frustum}; this file targets
 * the unpatched shapes. Descriptors are pinned so an added overload cannot silently match the wrong one.</p>
 */
@Mixin(LevelExtractor.class)
public class FabricLevelExtractorBlockEntityMixin {
    @WrapOperation(method = "extractVisibleBlockEntities(Lnet/minecraft/client/Camera;F"
            + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;"
                            + "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
                            + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)"
                            + "Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"))
    private BlockEntityRenderState caustica$skipVanillaBlockEntityExtraction(
            BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTicks,
            ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean isGloballyRendered,
            Operation<BlockEntityRenderState> original) {
        return dev.comfyfluffy.caustica.client.CausticaClientComposition.current().renderController().rtOwnsWorldRendering()
                ? null
                : original.call(dispatcher, blockEntity, partialTicks, breakProgress, isGloballyRendered);
    }
}
