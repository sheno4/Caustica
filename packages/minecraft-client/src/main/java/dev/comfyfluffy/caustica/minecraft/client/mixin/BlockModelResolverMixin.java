package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import dev.comfyfluffy.caustica.minecraft.client.entity.ContainedBlockSource;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attaches the resolved block after update has cleared and populated the display render state.
 * {@link BlockModelRenderStateMixin} uses it to capture geometry from the world model set.
 */
@Mixin(BlockModelResolver.class)
public class BlockModelResolverMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void caustica$recordContained(BlockModelRenderState renderState, BlockState blockState,
                                          BlockDisplayContext displayContext, CallbackInfo ci) {
        try (var hostWork = MinecraftHostTelemetry.work("entity.containedMetadata")) {
            ((ContainedBlockSource) renderState).caustica$setContainedBlock(blockState);
        }
    }
}
