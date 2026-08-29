package dev.comfyfluffy.caustica.minecraft.overlay;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuFrameUse;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntities;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.vulkan.VmaImage2D;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The vanilla targeted-block shape rendered at display resolution and occluded through the root TLAS. */
final class BlockOutlineFeature implements OverlayFeature {
    private final RtEntities entities;
    BlockOutlineFeature(RtEntities entities) { this.entities = entities; }
    private static final int PUSH_BYTES = 112;
    private static final float OUTLINE_ALPHA = 102f / 255f;
    private GpuDevice device;
    private OverlayPipelines.Pipeline pipeline;
    private OverlayPipelines.Pipeline compositePipeline;
    private VmaImage2D mask;
    private boolean maskNeedsInitialization;
    private final Matrix4f viewProj = new Matrix4f();
    private OverlayFramePool.Buffer vbo;
    private int vertexCount;
    private int tlasDescriptor;

    @Override public boolean prepare(GpuDevice device, OverlayFramePool pool, GpuFrameUse gpuUse,
            int worldTlasDescriptor, Matrix4fc worldViewProjection, int width, int height) {
        if (!CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_ENABLED.value() || worldTlasDescriptor == 0) return false;
        RtTerrain terrain = RtTerrain.currentOrNull();
        if (terrain == null) return false;
        var game = Minecraft.getInstance().gameRenderer.gameRenderState();
        if (game.guiRenderState.isHudHidden) return false;
        BlockOutlineRenderState state = game.levelRenderState.blockOutlineRenderState;
        if (state == null || !shouldRenderForGameMode(state.pos())) return false;
        BlockPos pos = state.pos();
        VoxelShape shape = state.shape();
        float baseX = pos.getX() - terrain.blockX, baseY = pos.getY() - terrain.blockY, baseZ = pos.getZ() - terrain.blockZ;
        FloatArrayList vertices = new FloatArrayList(72);
        shape.forAllEdges((x1,y1,z1,x2,y2,z2) -> {
            vertices.add((float)(baseX+x1)); vertices.add((float)(baseY+y1)); vertices.add((float)(baseZ+z1));
            vertices.add((float)(baseX+x2)); vertices.add((float)(baseY+y2)); vertices.add((float)(baseZ+z2));
        });
        vertexCount = vertices.size() / 3;
        if (vertexCount == 0) return false;
        ensureResources(device, gpuUse, width, height);
        float[] data = vertices.toFloatArray();
        vbo = pool.acquireVertex(device, (long)data.length * Float.BYTES, "block outline vbo");
        MemoryUtil.memFloatBuffer(vbo.mapped(), data.length).put(data);
        vbo.flush(0, (long)data.length * Float.BYTES);
        viewProj.set(worldViewProjection);
        tlasDescriptor = worldTlasDescriptor;
        return true;
    }

    private void ensureResources(GpuDevice gpu, GpuFrameUse use, int width, int height) {
        device = gpu;
        if (pipeline == null) {
            pipeline = new OverlayPipelines.Spec("block_outline/vertex.vert.spv", "block_outline/fragment.frag.spv")
                    .vertex(OverlayPipelines.VertexFormat.POSITION).topology(VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST)
                    .attachment(WorldOverlayPass.TARGET_FORMAT).build(gpu, "block outline");
            compositePipeline = new OverlayPipelines.Spec("overlay_composite/vertex.vert.spv", "overlay_composite/passthrough.frag.spv")
                    .blend(OverlayPipelines.Blend.ALPHA).attachment(WorldOverlayPass.TARGET_FORMAT).build(gpu, "block outline composite");
        }
        if (mask == null || mask.width() != width || mask.height() != height) {
            if (mask != null) { VmaImage2D old = mask; use.whenComplete(old::close); }
            mask = VmaImage2D.create(gpu, width, height, WorldOverlayPass.TARGET_FORMAT,
                    VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, "block outline mask");
            maskNeedsInitialization = true;
        }
    }

    @Override public void record(VkCommandBuffer cmd, long targetView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (maskNeedsInitialization) { WorldOverlayPass.initializeImage(cmd, stack, mask); maskNeedsInitialization = false; }
            WorldOverlayPass.beginColorRendering(cmd, stack, mask.view(), width, height, true);
            VK10.vkCmdBindVertexBuffers(cmd, 0, stack.longs(vbo.handle()), stack.longs(0));
            VK10.vkCmdSetLineWidth(cmd, 1f);
            ByteBuffer push = stack.malloc(PUSH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            viewProj.get(0, push);
            push.putFloat(64, entities.glowCamOffsetX()).putFloat(68, entities.glowCamOffsetY()).putFloat(72, entities.glowCamOffsetZ());
            push.putFloat(80, 0).putFloat(84, 0).putFloat(88, 0).putFloat(92, OUTLINE_ALPHA).putInt(96, tlasDescriptor);
            pipeline.bind(cmd, push, width, height);
            VK10.vkCmdDraw(cmd, vertexCount, 1, 0, 0);
            WorldOverlayPass.endRendering(cmd);
            WorldOverlayPass.memoryBarrier(cmd, stack);
            WorldOverlayPass.beginColorRendering(cmd, stack, targetView, width, height, false);
            compositePipeline.bind(cmd, stack.malloc(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, mask.sampledIndex().value()), width, height);
            VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
            WorldOverlayPass.endRendering(cmd);
        }
    }

    private static boolean shouldRenderForGameMode(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance(); Entity camera = mc.getCameraEntity();
        if (!(camera instanceof Player player)) return false;
        if (player.getAbilities().mayBuild) return true;
        Level level = mc.level; if (level == null) return false;
        BlockState state = level.getBlockState(pos);
        if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR) return state.getMenuProvider(level, pos) != null;
        ItemStack item = player.getMainHandItem(); BlockInWorld block = new BlockInWorld(level, pos, false);
        return !item.isEmpty() && (item.canBreakBlockInAdventureMode(block) || item.canPlaceOnBlockInAdventureMode(block));
    }

    @Override public void close() {
        if (pipeline != null) pipeline.close(); if (compositePipeline != null) compositePipeline.close(); if (mask != null) mask.close();
        pipeline = null; compositePipeline = null; mask = null; device = null;
    }
}
