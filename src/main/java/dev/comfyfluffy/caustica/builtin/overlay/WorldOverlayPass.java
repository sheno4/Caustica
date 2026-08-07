package dev.comfyfluffy.caustica.builtin.overlay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import java.util.ArrayList;
import java.util.List;

/**
 * The world-space overlay seam: full-res raster content prepared after the RT world has been upscaled
 * (nothing thin/crisp survives DLSS-RR, so overlays must not be traced/rastered at render res) and folded
 * into the shared transparent UI image before the hand/screen-effects/GUI layers draw over it. Registered
 * under {@link RenderStage#OVERLAY}, recorded once per frame from {@code GameRendererMixin} at the
 * before-hand seam via {@link RtComposite#recordOverlayPasses}, on its own late transient command buffer —
 * {@code record} cannot fold into {@link RtComposite}'s main frame recording because the world hasn't been
 * upscaled yet at that point.
 *
 * <p>This class owns the questions every overlay feature would otherwise re-answer: which image to
 * composite onto (a shared mod-owned overlay buffer — every feature draws into THAT, not the final UI
 * overlay directly, see {@link #overlayImage} below) and the inter-feature barriers, per-frame vertex
 * scratch ({@link OverlayFramePool}). Features implement {@link OverlayFeature}; pipelines come from
 * {@link OverlayPipelines}. Failure isolation is the engine's ({@code RenderPassManager} disables a pass
 * that throws), not a private latch.
 *
 * <p>Routing every feature through one shared buffer instead of blending straight onto vanilla's SDR
 * {@code main} keeps SDR/HDR presentation unified: {@link #record} folds that buffer into
 * {@link RtUiOverlay}'s transparent overlay before the vanilla GUI renders, so the GUI remains topmost and
 * the final present path only has one UI image to blend. The block outline applies its private MSAA
 * mask-resolve before its result reaches {@code overlayImage}; MSAA is the overlay edge-AA mechanism.
 *
 * <p>Like {@code SkyLutPass}, this pass gathers the Minecraft-side state it needs itself rather than
 * having it handed in: {@link RtComposite#currentGraphicsUse()} for the shared TLAS-lifetime completion
 * token, and {@code Minecraft.getInstance().gameRenderer.mainRenderTarget()} for the post-upscale render
 * target — the same object {@code GameRendererMixin}'s {@code this.mainRenderTarget} field holds at the
 * call site (see {@code RtUiOverlay.java}'s identical read).
 */
public final class WorldOverlayPass implements CausticaRenderPass {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("caustica", "world_overlay");

    /** The shared overlay buffer's + presented image's VkFormat ({@code GpuFormat.RGBA8_UNORM}). */
    public static final int TARGET_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;

    private final OverlayFramePool framePool = new OverlayFramePool();
    private final List<OverlayFeature> features =
            List.of(new GlowOutlineFeature(), new NameTagFeature(), new BlockOutlineFeature());

    // Shared world-overlay buffer every feature composites into. uiComposite* blends it into RtUiOverlay's
    // transparent target; RtUiOverlay owns the one final SDR/HDR blend to the real target.
    private GpuContext ctx;
    private GpuImage overlayImage;
    private OverlayPipelines.Pipeline uiCompositePipeline;
    private OverlayPipelines.ReadOnlyImageSet uiCompositeSet;

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public RenderStage stage() {
        return RenderStage.OVERLAY;
    }

    @Override
    public void create(PassSetup setup) {
        ctx = setup.context();
        uiCompositeSet = OverlayPipelines.readOnlyImageSet(
                ctx, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, "world overlay UI composite");
        // PREMULTIPLIED_ALPHA, not ALPHA: overlayImage ends up holding premultiplied content once more
        // than one feature has drawn into it (see Blend.ALPHA's doc) — blending it into the shared UI
        // image with the straight-alpha recipe would double-multiply by alpha.
        uiCompositePipeline = new OverlayPipelines.Spec(
                "overlay_composite/vertex.vert.spv", "overlay_composite/passthrough.frag.spv")
                .blend(OverlayPipelines.Blend.PREMULTIPLIED_ALPHA)
                .attachment(TARGET_FORMAT)
                .descriptorSetLayout(uiCompositeSet.layout)
                .build(ctx, "world overlay UI composite");
    }

    @Override
    public void resize(PassSetup setup, int displayWidth, int displayHeight) {
        if (overlayImage != null) {
            overlayImage.destroy();
        }
        overlayImage = ctx.createStorageImage(displayWidth, displayHeight, TARGET_FORMAT,
                "world overlay " + displayWidth + "x" + displayHeight, VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        uiCompositeSet.bind(ctx, overlayImage.view);
    }

    @Override
    public void record(PassFrame frame) {
        RtGpuExecutor.GraphicsUse graphicsUse = RtComposite.INSTANCE.currentGraphicsUse();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (graphicsUse == null || main == null || main.getColorTexture() == null || !RtUiOverlay.enabled()) {
            return;
        }
        int width = main.width;
        int height = main.height;
        try {
            List<OverlayFeature> ready = new ArrayList<>(features.size());
            for (OverlayFeature f : features) {
                if (f.prepare(ctx, framePool, graphicsUse, width, height)) {
                    ready.add(f);
                }
            }
            if (ready.isEmpty()) {
                return;
            }
            RenderTarget uiTarget = RtUiOverlay.beginCompositeLayer(main);
            long targetView = vkImageView(uiTarget.getColorTextureView());
            if (targetView == 0L) {
                CausticaMod.LOGGER.warn("World overlay: UI overlay target has no Vulkan image view; skipping");
                return;
            }
            recordDraws(frame.commandBuffer(), ready, targetView, width, height);
        } finally {
            framePool.endFrame(ctx, graphicsUse);
        }
    }

    private void recordDraws(VkCommandBuffer cmd, List<OverlayFeature> ready, long targetView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // host vertex writes visible

            long overlayView = overlayImage.view;
            beginColorRendering(cmd, stack, overlayView, width, height, true); // clear to transparent once
            endRendering(cmd);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            for (OverlayFeature f : ready) {
                f.record(cmd, overlayView, width, height);
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // this feature's writes visible to the next / final composite
            }

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world overlay UI composite")) {
                beginColorRendering(cmd, stack, targetView, width, height, false); // LOAD the transparent UI image
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, uiCompositePipeline.handle);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, uiCompositePipeline.layout, 0,
                        stack.longs(uiCompositeSet.set), null);
                VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
                endRendering(cmd);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // this composite's writes visible to whatever presents next
        }
    }

    @Override
    public void destroy() {
        for (OverlayFeature f : features) {
            f.destroy();
        }
        if (uiCompositePipeline != null && ctx != null) {
            uiCompositePipeline.destroy(ctx.vk());
            uiCompositeSet.destroy(ctx.vk());
        }
        uiCompositePipeline = null;
        uiCompositeSet = null;
        if (overlayImage != null) {
            overlayImage.destroy();
            overlayImage = null;
        }
        framePool.destroy();
    }

    // ---- Recording helpers shared by features ----

    /**
     * Begin a one-attachment dynamic-rendering pass on {@code view} (GENERAL layout) and set the
     * viewport/scissor. {@code clear} = start from transparent black (mask passes); otherwise the existing
     * content is loaded (composite passes). Balance with {@link #endRendering}.
     */
    static void beginColorRendering(VkCommandBuffer cmd, MemoryStack stack, long view, int width, int height, boolean clear) {
        VkRenderingAttachmentInfo.Buffer colorAttach = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(clear ? VK10.VK_ATTACHMENT_LOAD_OP_CLEAR : VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        if (clear) {
            VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
            clearValue.get(0).color().float32(stack.floats(0f, 0f, 0f, 0f));
            colorAttach.get(0).clearValue(clearValue.get(0));
        }
        VkRect2D renderArea = VkRect2D.calloc(stack);
        renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
        renderArea.extent().set(width, height);
        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(renderArea).layerCount(1).pColorAttachments(colorAttach);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
        VK10.vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.get(0).extent().set(width, height);
        VK10.vkCmdSetScissor(cmd, 0, scissor);
    }

    /**
     * Begin a one-attachment dynamic-rendering pass on the multisample {@code msaaView}, always clearing to
     * transparent black (mask passes only — there is nothing sensible to "load" into a fresh multisample
     * image from a single-sample source). {@code resolveView} receives the driver's per-pixel sample average
     * when the pass ends ({@link #endRendering}) — {@code VK_RESOLVE_MODE_AVERAGE_BIT} is the only mode
     * color attachments support, which is exactly coverage-weighted anti-aliasing for a flat-colour mask.
     */
    static void beginMsaaColorRendering(VkCommandBuffer cmd, MemoryStack stack, long msaaView, long resolveView,
                                        int width, int height) {
        VkRenderingAttachmentInfo.Buffer colorAttach = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(msaaView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .resolveMode(VK12.VK_RESOLVE_MODE_AVERAGE_BIT)
                .resolveImageView(resolveView).resolveImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE); // only the resolved target's contents matter
        VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
        clearValue.get(0).color().float32(stack.floats(0f, 0f, 0f, 0f));
        colorAttach.get(0).clearValue(clearValue.get(0));

        VkRect2D renderArea = VkRect2D.calloc(stack);
        renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
        renderArea.extent().set(width, height);
        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(renderArea).layerCount(1).pColorAttachments(colorAttach);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
        VK10.vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.get(0).extent().set(width, height);
        VK10.vkCmdSetScissor(cmd, 0, scissor);
    }

    static void endRendering(VkCommandBuffer cmd) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
