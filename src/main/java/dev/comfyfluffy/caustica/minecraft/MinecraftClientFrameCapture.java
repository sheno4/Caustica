package dev.comfyfluffy.caustica.minecraft;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.Optional;

/** Samples Minecraft client, texture, and terrain state exactly once for a render frame. */
final class MinecraftClientFrameCapture {
    private static final float TO_RADIANS = (float) (Math.PI / 180.0);
    private static final Identifier SUN_SPRITE_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_SPRITE_IDS = moonSpriteIds();

    private MinecraftClientFrameCapture() { }

    static MinecraftCapturedFrame capture(Minecraft minecraft, double cameraY, double metersPerSceneUnit) {
        Optional<MinecraftCapturedFrame.Celestial> celestial = celestial(minecraft, cameraY, metersPerSceneUnit);
        return new MinecraftCapturedFrame(celestial, celestial.flatMap(value -> atlas(minecraft, value)),
                helmet(minecraft), RtTerrain.retainedLightSnapshot());
    }

    private static Optional<MinecraftCapturedFrame.Celestial> celestial(
            Minecraft minecraft, double cameraY, double metersPerSceneUnit) {
        if (minecraft.player == null || minecraft.level == null
                || !Level.OVERWORLD.equals(minecraft.level.dimension())) return Optional.empty();
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
        return Optional.of(new MinecraftCapturedFrame.Celestial(
                probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * TO_RADIANS,
                probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * TO_RADIANS,
                probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * TO_RADIANS,
                probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial),
                probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(),
                minecraft.level.getSeaLevel(), cameraY, metersPerSceneUnit,
                MinecraftLightingCalibration.current()));
    }

    private static Optional<MinecraftCapturedFrame.CelestialAtlas> atlas(
            Minecraft minecraft, MinecraftCapturedFrame.Celestial celestial) {
        try {
            TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
            if (!(atlas.getTextureView() instanceof VulkanGpuTextureView view)
                    || view.texture().getFormat() != GpuFormat.RGBA8_UNORM) return Optional.empty();
            TextureAtlasSprite sun = atlas.getSprite(SUN_SPRITE_ID);
            int phase = Math.clamp(celestial.moonPhaseIndex(), 0, MOON_SPRITE_IDS.length - 1);
            TextureAtlasSprite moon = atlas.getSprite(MOON_SPRITE_IDS[phase]);
            return Optional.of(new MinecraftCapturedFrame.CelestialAtlas(view.texture(), view.baseMipLevel(),
                    view.mipLevels(), uv(sun), uv(moon)));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private static Optional<LightDescriptor.Spot> helmet(Minecraft minecraft) {
        if (minecraft.player == null
                || !minecraft.player.getItemBySlot(EquipmentSlot.HEAD).is(CausticaItems.SPOTLIGHT_HELMET)) {
            return Optional.empty();
        }
        Vec3 eye = minecraft.player.getEyePosition();
        Vector3fc forward = minecraft.gameRenderer.mainCamera().forwardVector();
        return Optional.of(dev.comfyfluffy.caustica.minecraft.provider.MinecraftLightProvider.helmetSpot(
                eye.x + forward.x() * 0.18, eye.y - 0.08 + forward.y() * 0.18,
                eye.z + forward.z() * 0.18, forward.x(), forward.y(), forward.z()));
    }

    private static MinecraftCapturedFrame.Uv uv(TextureAtlasSprite sprite) {
        return new MinecraftCapturedFrame.Uv(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
    }

    private static Identifier[] moonSpriteIds() {
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) {
            ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        }
        return ids;
    }
}
