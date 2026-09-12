package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.minecraft.client.entity.ContainedBlockSource;
import dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures resolved block displays from the world model set when submission targets the RT collector.
 * The display model set can use unsupported submission paths, including Fabric's extra mesh input.
 * The resolved display transform still applies to the replacement geometry. The source state is
 * cleared with the display state so a reused render state cannot capture a previous block.
 */
@Mixin(BlockModelRenderState.class)
public abstract class BlockModelRenderStateMixin implements ContainedBlockSource {
    @Shadow @Nullable private Matrix4fc transformation;
    @Shadow @Nullable private Matrix4fc specialRendererTransformation;

    @Unique private BlockState caustica$containedState;

    @Override
    public void caustica$setContainedBlock(@Nullable BlockState state) {
        this.caustica$containedState = state;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void caustica$clearContained(CallbackInfo ci) {
        try (var hostWork = MinecraftHostTelemetry.work("entity.containedClear")) {
            this.caustica$containedState = null;
        }
    }

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void caustica$captureForRt(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                       int externalLightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        try (var hostWork = MinecraftHostTelemetry.work("entity.containedSubmit")) {
            if (this.caustica$containedState == null || !(submitNodeCollector instanceof RtEntityCollector rt)) {
                return;
            }
            // Both display paths normalize an identity transform to null.
            Matrix4fc transform = this.transformation != null ? this.transformation : this.specialRendererTransformation;
            rt.captureBlockState(this.caustica$containedState, transform, poseStack);
            ci.cancel();
        }
    }
}
