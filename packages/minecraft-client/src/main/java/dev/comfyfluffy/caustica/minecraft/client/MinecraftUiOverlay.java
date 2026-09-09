package dev.comfyfluffy.caustica.minecraft.client;

import java.util.Optional;

import org.joml.Vector4f;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.api.vulkan.OwnedGpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import org.lwjgl.vulkan.VK10;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Routes world overlays and vanilla GUI into one transparent RGBA8 target. SDR composites it over the
 * main target after GUI rendering; HDR composites it at paper white after the world's display transform.
 *
 * <p>Composite blend: vanilla GUI pipelines use {@code BlendFunction.TRANSLUCENT} (colour {@code SRC_ALPHA,
 * ONE_MINUS_SRC_ALPHA}; alpha {@code ONE, ONE_MINUS_SRC_ALPHA}), so drawing onto a cleared target
 * accumulates <em>premultiplied</em> colour ({@code rgb = C*A}, {@code a = A}). The composite therefore uses
 * premultiplied-over ({@code TRANSLUCENT_PREMULTIPLIED_ALPHA}).
 *
 * <p>Depth: the overlay clears depth to 0.0 each frame, exactly as {@code GameRenderer.render} clears the
 * main depth right before the GUI. Blur ({@code GameRenderer.processBlurEffect}) still operates on the real
 * main target, so the world behind screens is blurred as usual and the overlay composites over the result.
 */
public final class MinecraftUiOverlay {
    private static final Vector4f TRANSPARENT = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);

    /** Fullscreen blit that composites the premultiplied overlay over the destination (premultiplied-over). */
    private static final RenderPipeline COMPOSITE_PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation("pipeline/caustica_ui_overlay_composite")
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    private final MinecraftRtRuntime runtime;
    private TextureTarget overlay;
    private boolean usedThisFrame;
    // Hand, world overlays and GUI share one clear, before the first draw of the frame.
    private boolean overlayClearedThisFrame;
    private MinecraftVulkanImage ownedImage;

    public MinecraftUiOverlay(MinecraftRtRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    /** The composite pipeline's shaders become available only after game resources finish loading. */
    public boolean enabled() {
        return runtime.frameActive()
                && Minecraft.getInstance().isGameLoadFinished();
    }

    /** Whether the overlay holds this frame's UI (for the HDR present path to composite + consume). */
    public boolean populatedThisFrame() {
        return usedThisFrame && overlay != null;
    }

    /** Mark the overlay consumed by the HDR present composite (so it isn't reused next frame). */
    public void markConsumed() {
        usedThisFrame = false;
    }

    public UiPresentationResources capturePresentation() {
        return new UiPresentationResources(enabled(), populatedThisFrame(), ownedImage,
                overlay == null ? 0 : overlay.width, overlay == null ? 0 : overlay.height);
    }

    /**
     * Prepare the overlay (sized to {@code main}, cleared transparent with depth cleared to 0.0) and return
     * it so {@code GuiRenderer.draw} renders the GUI into it instead of the main target. Called from the
     * {@code GuiRendererMixin} redirect on the render thread.
     */
    public RenderTarget beginAndRedirect(RenderTarget main) {
        try (var ignored = runtime.profileStage("ui.redirect")) {
            return prepare(main);
        }
    }

    /** The sampled overlay image consumed by renderer-owned UI commands. */
    public OwnedGpuImage uiPassTarget(RenderTarget main) {
        try (var ignored = runtime.profileStage("ui.prepare")) {
            TextureTarget target = prepare(main);
            VulkanDeviceContext gpu = runtime.vulkanContextOrNull();
            if (gpu == null || !(target.getColorTextureView() instanceof VulkanGpuTextureView view)) return null;
            if (ownedImage == null || !ownedImage.wraps(gpu, view, target.width, target.height)) {
                MinecraftVulkanImage replacement = MinecraftVulkanImage.sampled(
                        gpu, view, target.width, target.height, VK10.VK_FORMAT_R8G8B8A8_UNORM);
                MinecraftVulkanImage old = ownedImage;
                ownedImage = replacement;
                if (old != null) old.close();
            }
            return ownedImage;
        }
    }

    /** Begin an active render frame with no populated UI, even if prior presentation was skipped. */
    public void beginFrame() {
        usedThisFrame = false;
        overlayClearedThisFrame = false;
    }

    /**
     * Ensure the overlay exists, sized to {@code main}, and cleared (transparent + depth 0.0) exactly once
     * this frame, then mark it used. Both the hand redirect and the GUI redirect funnel through here so the
     * overlay is cleared before the hand (which renders first) and not wiped before the GUI.
     */
    private TextureTarget prepare(RenderTarget main) {
        TextureTarget target = ensureSized(main);
        if (!overlayClearedThisFrame) {
            CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
            enc.clearColorAndDepthTextures(target.getColorTexture(), TRANSPARENT, target.getDepthTexture(), 0.0);
            overlayClearedThisFrame = true;
        }
        usedThisFrame = true;
        return target;
    }

    /**
     * HDR mode: redirect a world-space overlay render (the held-item/hand, then the fire/underwater/
     * view-blocking screen effects) into the overlay so it composites over the HDR world at paper white,
     * via the render-system output overrides honoured by {@code PreparedRenderType}. Both share the overlay's
     * color+depth (cleared once per frame), matching vanilla where hand and screen effects share the main
     * target's depth without a clear between them. Must be paired with {@link #endOutputRedirect()}.
     */
    public void beginOutputRedirect(RenderTarget main) {
        try (var ignored = runtime.profileStage("ui.redirect")) {
            TextureTarget target = prepare(main);
            RenderSystem.outputColorTextureOverride = target.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
        }
    }

    public void endOutputRedirect() {
        try (var ignored = runtime.profileStage("ui.redirect")) {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }
    }

    /**
     * Composite the overlay over the real main target. Called once per frame from {@code GameRendererMixin}
     * after {@code GuiRenderer.render} returns.
     */
    public void compositeIfUsed() {
        if (!usedThisFrame || overlay == null) {
            usedThisFrame = false;
            return;
        }
        if (runtime.isHdrPresentActive()) {
            // Keep the overlay populated until HDR presentation consumes it.
            return;
        }
        usedThisFrame = false;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (main == null || main.getColorTextureView() == null) {
            return;
        }
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = enc.createRenderPass(() -> "UI overlay composite", main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(COMPOSITE_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", overlay.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    private TextureTarget ensureSized(RenderTarget main) {
        if (overlay == null) {
            overlay = new TextureTarget("caustica UI overlay", main.width, main.height, true, GpuFormat.RGBA8_UNORM);
        } else if (overlay.width != main.width || overlay.height != main.height) {
            overlay.resize(main.width, main.height);
            // The replacement textures have not received this frame's transparent/depth clear.
            overlayClearedThisFrame = false;
        }
        return overlay;
    }

    public void destroy() {
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
        usedThisFrame = false;
        overlayClearedThisFrame = false;
        MinecraftVulkanImage image = ownedImage;
        TextureTarget target = overlay;
        ownedImage = null;
        overlay = null;
        try {
            if (image != null) image.close();
        } finally {
            if (target != null) target.destroyBuffers();
        }
    }

}
