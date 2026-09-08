package dev.comfyfluffy.caustica.minecraft.client.overlay;

import dev.comfyfluffy.caustica.vulkan.VmaMappedHostBuffer;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import dev.comfyfluffy.caustica.api.resource.FrameResources;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectGraphics;
import dev.comfyfluffy.caustica.vulkan.VmaImage2D;
import dev.comfyfluffy.caustica.minecraft.client.entity.RtEntities;

/**
 * Entity glow (Glowing-effect) outline — full-res, post-upscale, depth-less. Two passes:
 * <ol>
 *   <li>Re-rasterize this frame's glowing entities (their CPU-side capture positions, already kept around
 *   by {@link RtEntities} alongside retained scene-mesh capture) with a trivial unlit pipeline into a full-res RGBA8 mask
 *   (rgb = the entity's vanilla outline colour, a = coverage) — a mod-owned storage image. The camera
 *   transform mirrors the one {@code world.rgen} used this frame, so the silhouette lands pixel-exact on
 *   the ray-traced entity.</li>
 *   <li>Sobel-edge that mask and blend the ~2px boundary onto the composite target via fixed-function
 *   alpha blending.</li>
 * </ol>
 * Matches vanilla's silhouette-through-walls look without ever touching a depth buffer. Never makes the
 * entity itself emissive.
 */
final class GlowOutlineFeature implements OverlayFeature {
    private final RtEntities entities;
    private final ResourceFactory resources;
    private ResourceOwner maskOwner;
    GlowOutlineFeature(RtEntities entities, ResourceFactory resources) {
        this.entities = entities; this.resources = resources;
    }
    // View projection at 0, camera offset at 64, color at 80, and TLAS index at 96; padded to 112 bytes.
    private static final int MASK_PUSH_BYTES = 112;
    private static final int MASK_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;

    private ShaderObjectGraphics maskPipeline;
    private ShaderObjectGraphics compositePipeline;
    private VmaImage2D maskImage;
    private boolean maskNeedsInitialization;

    // This frame's prepared draw data (valid between prepare() returning true and record()).
    private final Matrix4f viewProj = new Matrix4f();
    private float camOffX, camOffY, camOffZ;
    private VmaMappedHostBuffer vbo;
    private VmaMappedHostBuffer ibo;
    private int[] firstIndex;
    private int[] indexCount;
    private float[] colorRgba;
    private int drawCount;

    @Override
    public boolean prepare(GpuDevice device, OverlayFrameBuffers pool, FrameResources frameResources,
                           int worldTlas, Matrix4fc worldViewProjection, int width, int height) {
        if (!RtEntities.glowEnabled()) {
            return false;
        }
        List<RtEntities.GlowEntity> batches = entities.glowBatches();
        if (batches.isEmpty()) {
            return false;
        }
        ensureResources(device, frameResources, width, height);

        // Merge every glowing entity's mesh into one vertex/index pair (indices rebased onto the merged
        // vertex buffer); one draw per entity so each can push its own outline colour.
        int totalVerts = 0;
        int totalIdx = 0;
        for (RtEntities.GlowEntity e : batches) {
            totalVerts += e.verts().length / 3;
            totalIdx += e.idx().length;
        }
        float[] mergedVerts = new float[totalVerts * 3];
        int[] mergedIdx = new int[totalIdx];
        drawCount = batches.size();
        firstIndex = new int[drawCount];
        indexCount = new int[drawCount];
        colorRgba = new float[drawCount * 4];
        int vOff = 0;
        int iOff = 0;
        int vBase = 0;
        for (int i = 0; i < drawCount; i++) {
            RtEntities.GlowEntity e = batches.get(i);
            float[] verts = e.verts();
            System.arraycopy(verts, 0, mergedVerts, vOff, verts.length);
            int[] idx = e.idx();
            firstIndex[i] = iOff;
            indexCount[i] = idx.length;
            for (int j = 0; j < idx.length; j++) {
                mergedIdx[iOff + j] = idx[j] + vBase;
            }
            int color = e.color();
            colorRgba[i * 4] = ((color >> 16) & 0xFF) / 255f;
            colorRgba[i * 4 + 1] = ((color >> 8) & 0xFF) / 255f;
            colorRgba[i * 4 + 2] = (color & 0xFF) / 255f;
            colorRgba[i * 4 + 3] = ((color >>> 24) & 0xFF) / 255f;
            vOff += verts.length;
            iOff += idx.length;
            vBase += verts.length / 3;
        }

        vbo = pool.acquireVertex(device, (long) mergedVerts.length * Float.BYTES, "glow vbo");
        ibo = pool.acquireIndex(device, (long) mergedIdx.length * Integer.BYTES, "glow ibo");
        vbo.mapped().order(ByteOrder.nativeOrder()).asFloatBuffer().put(mergedVerts);
        ibo.mapped().order(ByteOrder.nativeOrder()).asIntBuffer().put(mergedIdx);
        vbo.flush(0L, (long) mergedVerts.length * Float.BYTES);
        ibo.flush(0L, (long) mergedIdx.length * Integer.BYTES);

        viewProj.set(worldViewProjection);
        camOffX = entities.glowCamOffsetX();
        camOffY = entities.glowCamOffsetY();
        camOffZ = entities.glowCamOffsetZ();
        return true;
    }

    private void ensureResources(GpuDevice device, FrameResources frameResources, int width, int height) {
        if (maskPipeline == null) {
            maskPipeline = new OverlayPipelines.Spec("entity_glow/vertex.vert.spv", "entity_glow/fragment.frag.spv")
                    .vertex(OverlayPipelines.POSITION)
                    .build(device);
        }
        if (compositePipeline == null) {
            compositePipeline = new OverlayPipelines.Spec("overlay_composite/vertex.vert.spv", "overlay_composite/glow.frag.spv")
                    .blend(OverlayPipelines.ALPHA_BLEND)
                    .build(device);
        }
        if (maskImage == null || maskImage.width() != width || maskImage.height() != height) {
            VmaImage2D replacement = VmaImage2D.create(device, width, height, MASK_FORMAT,
                    VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                    "glow outline mask " + width + "x" + height);
            ResourceOwner replacementOwner;
            try {
                replacementOwner = resources.create(replacement::close);
            } catch (RuntimeException | Error failure) {
                try (replacement) { throw failure; }
            }
            ResourceOwner previousOwner = maskOwner;
            maskImage = replacement;
            maskOwner = replacementOwner;
            maskNeedsInitialization = true;
            if (previousOwner != null) previousOwner.close();
        }
        frameResources.retain(maskOwner);
    }

    @Override
    public void record(VkCommandBuffer cmd, long targetView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (maskNeedsInitialization) {
                WorldOverlayPass.initializeImage(cmd, stack, maskImage);
                maskNeedsInitialization = false;
            }
            {
                WorldOverlayPass.beginColorRendering(cmd, stack, maskImage.view(), width, height, true);
                VK10.vkCmdBindVertexBuffers(cmd, 0, stack.longs(vbo.buffer()), stack.longs(0L));
                VK10.vkCmdBindIndexBuffer(cmd, ibo.buffer(), 0, VK10.VK_INDEX_TYPE_UINT32);
                ByteBuffer push = stack.malloc(MASK_PUSH_BYTES);
                viewProj.get(0, push);
                for (int i = 0; i < drawCount; i++) {
                    push.putFloat(64, camOffX).putFloat(68, camOffY).putFloat(72, camOffZ);
                    push.putFloat(80, colorRgba[i * 4]).putFloat(84, colorRgba[i * 4 + 1])
                            .putFloat(88, colorRgba[i * 4 + 2]).putFloat(92, colorRgba[i * 4 + 3]);
                    push.putInt(96, 0);
                    maskPipeline.bind(cmd, push, width, height);
                    VK10.vkCmdDrawIndexed(cmd, indexCount[i], 1, firstIndex[i], 0, 0);
                }
                WorldOverlayPass.endRendering(cmd);
            }

            WorldOverlayPass.memoryBarrier(cmd, stack);

            {
                WorldOverlayPass.beginColorRendering(cmd, stack, targetView, width, height, false);
                ByteBuffer compositePush = stack.malloc(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(0, maskImage.sampledIndex().value());
                compositePipeline.bind(cmd, compositePush, width, height);
                VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
                WorldOverlayPass.endRendering(cmd);
            }
        }
    }

    @Override
    public void close() {
        try (var maskShader = maskPipeline; var compositeShader = compositePipeline; var imageOwner = maskOwner) {
            maskPipeline = null;
            compositePipeline = null;
            maskOwner = null;
            maskImage = null;
        }
    }
}
