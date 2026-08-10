package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.rt.RtColor;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4fc;

/** Converts Minecraft lifecycle and camera state into host-neutral renderer inputs. */
public final class MinecraftFrameAdapter {
    public static final MinecraftFrameAdapter INSTANCE = new MinecraftFrameAdapter();

    private static final double METERS_PER_WORLD_UNIT = 1.0;
    private static final int DEFAULT_WATER_COLOR = 0x3F75E8;

    private ClientLevel identifiedLevel;
    private long nextSceneId;
    private long sceneId;

    private MinecraftFrameAdapter() {
    }

    public void tickRuntime(Minecraft client) {
        ClientLevel level = client.level;
        long currentSceneId = identify(level);
        var target = client.gameRenderer.mainRenderTarget();
        boolean startupSceneReady = level != null && client.player != null
                && RtTerrain.isSectionReady(client.player.blockPosition());
        RtRuntime.INSTANCE.tick(level != null, startupSceneReady, currentSceneId,
                target != null ? target.width : 0, target != null ? target.height : 0,
                client::invalidateSurfaceConfiguration);
    }

    public FrameSnapshot capture(Minecraft client, Matrix4fc projection, Matrix4fc viewRotation,
                                 double cameraX, double cameraY, double cameraZ) {
        ClientLevel level = client.level;
        BlockPos cameraBlockPos = new BlockPos(Mth.floor(cameraX), Mth.floor(cameraY), Mth.floor(cameraZ));
        boolean submerged = false;
        int waterColor = DEFAULT_WATER_COLOR;
        if (level != null) {
            FluidState fluid = level.getFluidState(cameraBlockPos);
            submerged = fluid.is(FluidTags.WATER)
                    && cameraY < cameraBlockPos.getY() + fluid.getHeight(level, cameraBlockPos);
            waterColor = BiomeColors.getAverageWaterColor(level, cameraBlockPos);
        }
        float[] medium = RtColor.linearBt709ToAcesCg(
                RtColor.srgbToLinear(((waterColor >> 16) & 0xFF) / 255.0),
                RtColor.srgbToLinear(((waterColor >> 8) & 0xFF) / 255.0),
                RtColor.srgbToLinear((waterColor & 0xFF) / 255.0));
        return new FrameSnapshot(projection, viewRotation, cameraX, cameraY, cameraZ,
                submerged, new FrameSnapshot.LinearRgb(medium[0], medium[1], medium[2]),
                System.nanoTime() / 1.0e9, METERS_PER_WORLD_UNIT, identify(level));
    }

    private long identify(ClientLevel level) {
        if (level != identifiedLevel) {
            identifiedLevel = level;
            sceneId = level == null ? 0L : ++nextSceneId;
        }
        return sceneId;
    }
}
