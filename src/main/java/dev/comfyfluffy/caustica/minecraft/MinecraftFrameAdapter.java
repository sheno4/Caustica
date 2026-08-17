package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.color.ColorTransforms;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.minecraft.damage.MinecraftDamageModifierPass;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftMaterialSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
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
        MinecraftVulkanBackend.installCurrent();
        ClientLevel level = client.level;
        long currentSceneId = identify(level);
        RtRuntime.INSTANCE.observeWorld(level, currentSceneId);
        if (!(client.gui.overlay() instanceof LoadingOverlay)) {
            RtRuntime.INSTANCE.observeResourcePackAvailable();
        }
        var target = client.gameRenderer.mainRenderTarget();
        boolean startupSceneReady = level != null && client.player != null
                && RtTerrain.isSectionReady(client.player.blockPosition());
        RtRuntime.INSTANCE.tick(captureSceneResources(client), startupSceneReady, currentSceneId,
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
        float[] medium = ColorTransforms.linearBt709ToAcesCg(
                ColorTransforms.srgbToLinear(((waterColor >> 16) & 0xFF) / 255.0),
                ColorTransforms.srgbToLinear(((waterColor >> 8) & 0xFF) / 255.0),
                ColorTransforms.srgbToLinear((waterColor & 0xFF) / 255.0));
        FrameSnapshot.CameraMedium cameraMedium = submerged
                ? new FrameSnapshot.CameraMedium(new MaterialHandle(MinecraftMaterialSource.WATER),
                new FrameSnapshot.LinearRgb(medium[0], medium[1], medium[2])) : null;
        MinecraftDamageModifierPass damagePass = RtRuntime.INSTANCE.renderPass(
                MinecraftDamageModifierPass.ID, MinecraftDamageModifierPass.class);
        if (damagePass != null) {
            damagePass.capture(level);
        }
        RtTerrain terrain = RtTerrain.currentOrNull();
        SceneOrigin sceneOrigin = terrain != null ? terrain.sceneOrigin() : SceneOrigin.ZERO;
        return new FrameSnapshot(projection, viewRotation, cameraX, cameraY, cameraZ,
                sceneOrigin, cameraMedium, CausticaConfig.Rt.Composite.WATER_WAVES.value(),
                System.nanoTime() / 1.0e9, METERS_PER_WORLD_UNIT, identify(level));
    }

    public SceneResources captureSceneResources(Minecraft client) {
        if (client.level == null) {
            return SceneResources.EMPTY;
        }
        return new SceneResources(true);
    }

    public UiPresentationResources captureUiPresentation() {
        return snapshotUiPresentation(MinecraftUiOverlay.enabled(),
                MinecraftUiOverlay.populatedThisFrame(),
                MinecraftUiOverlay.overlayColorImage(), MinecraftUiOverlay.overlayColorView(),
                MinecraftUiOverlay.overlayWidth(), MinecraftUiOverlay.overlayHeight());
    }

    static UiPresentationResources snapshotUiPresentation(boolean enabled, boolean populated,
                                                           long colorImage, long colorView,
                                                           int width, int height) {
        return new UiPresentationResources(enabled, populated, colorImage, colorView, width, height);
    }

    private long identify(ClientLevel level) {
        if (level != identifiedLevel) {
            identifiedLevel = level;
            sceneId = level == null ? 0L : ++nextSceneId;
        }
        return sceneId;
    }
}
