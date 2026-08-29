package dev.comfyfluffy.caustica.minecraft.overlay;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.api.gpu.GpuFrameUse;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.pass.UiSetup;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;
import dev.comfyfluffy.caustica.vulkan.VmaImage2D;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft world-space overlays recorded directly into the renderer-owned display-resolution UI layer.
 * The engine supplies the rendered camera, root-scene TLAS descriptor, command buffer, and completion
 * reservation through {@link UiFrame}; the pass owns only feature pipelines and transient vertex storage.
 */
public final class WorldOverlayPass implements Pass<UiFrame> {
    public static final ResourceId ID = ResourceId.of("caustica", "world_overlay");

    /** Renderer UI-layer VkFormat. */
    public static final int TARGET_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;

    private final OverlayFramePool framePool = new OverlayFramePool();
    private final List<OverlayFeature> features =
            List.of(new GlowOutlineFeature(), new NameTagFeature(), new BlockOutlineFeature());
    private final GpuDevice device;

    public WorldOverlayPass(UiSetup setup) {
        if (setup.layerFormat() != TARGET_FORMAT) {
            throw new IllegalArgumentException("Minecraft overlay requires RGBA8_UNORM UI layer");
        }
        device = setup.gpu();
    }

    @Override
    public void record(UiFrame frame) {
        GpuFrameUse gpuUse = frame.gpuUse();
        int width = frame.layer().width();
        int height = frame.layer().height();
        try {
            List<OverlayFeature> ready = new ArrayList<>(features.size());
            for (OverlayFeature f : features) {
                if (f.prepare(device, framePool, gpuUse,
                        frame.rootSceneTlasDescriptor().index().value(),
                        new Matrix4f().set(frame.worldViewProjection()),
                        width, height)) {
                    ready.add(f);
                }
            }
            if (ready.isEmpty()) {
                return;
            }
            recordDraws(frame.commandBuffer(), ready, frame.layer().view(), width, height);
        } finally {
            framePool.endFrame(gpuUse);
        }
    }

    private void recordDraws(VkCommandBuffer cmd, List<OverlayFeature> ready, long targetView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            memoryBarrier(cmd, stack);

            for (OverlayFeature f : ready) {
                f.record(cmd, targetView, width, height);
                memoryBarrier(cmd, stack);
            }
        }
    }

    @Override
    public void close() {
        for (OverlayFeature f : features) {
            f.close();
        }
        framePool.close();
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
        VK14.vkCmdBeginRendering(cmd, renderingInfo);

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
        VK14.vkCmdBeginRendering(cmd, renderingInfo);

        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
        VK10.vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.get(0).extent().set(width, height);
        VK10.vkCmdSetScissor(cmd, 0, scissor);
    }

    static void endRendering(VkCommandBuffer cmd) {
        VK14.vkCmdEndRendering(cmd);
    }

    static void memoryBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        org.lwjgl.vulkan.VkMemoryBarrier2.Buffer barrier = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default().srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_MEMORY_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_MEMORY_READ_BIT | VK13.VK_ACCESS_2_MEMORY_WRITE_BIT);
        VK14.vkCmdPipelineBarrier2(commandBuffer,
                org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    static void initializeImage(VkCommandBuffer commandBuffer, MemoryStack stack, VmaImage2D image) {
        org.lwjgl.vulkan.VkImageMemoryBarrier2.Buffer barrier =
                org.lwjgl.vulkan.VkImageMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default().srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE)
                .srcAccessMask(VK13.VK_ACCESS_2_NONE)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_MEMORY_READ_BIT | VK13.VK_ACCESS_2_MEMORY_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image.image());
        barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK14.vkCmdPipelineBarrier2(commandBuffer,
                org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
    }
}
