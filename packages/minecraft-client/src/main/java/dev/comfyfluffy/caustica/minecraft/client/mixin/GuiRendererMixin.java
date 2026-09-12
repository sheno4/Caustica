package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftUiOverlay;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes GUI draw ranges and their after-blur depth clear into the shared RT overlay. The draw method
 * fetches its destination once, so this expression also keeps all ranges on the same target.
 * GameRendererMixin composites the overlay after GUI rendering; the world's blur still operates on
 * the real main target through GameRenderer.processBlurEffect.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@ModifyExpressionValue(
			method = "draw",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget caustica$redirectGuiToOverlay(RenderTarget original) {
		try (var hostWork = MinecraftHostTelemetry.work("ui.redirectGui")) {
			MinecraftUiOverlay overlay = CausticaClientComposition.current().uiOverlay();
			if (original != null && overlay.enabled()) {
				return overlay.beginAndRedirect(original);
			}
			return original;
		}
	}
}
