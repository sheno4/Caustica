package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.minecraft.MinecraftUiOverlay;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Redirect the vanilla GUI/HUD into the active RT session's transparent overlay target. In SDR the
 * composite-back reproduces vanilla, and {@code WorldOverlayPass}'s world-space overlay composite runs at
 * that same seam. {@code GuiRenderer.draw} fetches the
 * destination via {@code gameRenderer.mainRenderTarget()} once and uses it for every GUI draw range (and the
 * after-blur depth clear), so redirecting that single expression routes all GUI rendering into the overlay.
 * The overlay is composited back over the world after {@code GuiRenderer.render} returns (see {@code
 * GameRendererMixin}) — its {@code draw} TAIL did not fire on in-game HUD frames. Blur is unaffected —
 * {@code GameRenderer.processBlurEffect} operates on the real main target.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@ModifyExpressionValue(
			method = "draw",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget caustica$redirectGuiToOverlay(RenderTarget original) {
		if (original != null && MinecraftUiOverlay.enabled()) {
			return MinecraftUiOverlay.beginAndRedirect(original);
		}
		return original;
	}
}
