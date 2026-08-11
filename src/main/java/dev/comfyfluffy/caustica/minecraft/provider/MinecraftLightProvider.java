package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.LightSink;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.OptionValues;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.sky.SkyLutPass;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import dev.comfyfluffy.caustica.minecraft.CausticaItems;
import dev.comfyfluffy.caustica.minecraft.MinecraftLightingCalibration;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftLightProvider implements LightProvider {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_lights");
    private static final long HELMET_LIGHT_KEY = 1L;
    private static final long SUN_LIGHT_KEY = 2L;
    private static final long MOON_LIGHT_KEY = 3L;
    private static final double TO_RADIANS = Math.PI / 180.0;
    private static final double SURFACE_TO_TOP_ILLUMINANCE = 100_000.0 / 128_000.0;

    @Override
    public void submitLights(LightSink sink) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (minecraft.level != null && Level.OVERWORLD.equals(minecraft.level.dimension())) {
            float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
            OptionValues options = CausticaApi.options().options(MinecraftProvidersExtension.ID);
            MinecraftLightingCalibration lighting = MinecraftLightingCalibration.current();
            CelestialFrame frame = new CelestialFrame(
                    probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * TO_RADIANS,
                    probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * TO_RADIANS,
                    options.get(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES) * TO_RADIANS,
                    lighting.sunIlluminanceLux() * SURFACE_TO_TOP_ILLUMINANCE,
                    lighting.moonIlluminanceLux() * SURFACE_TO_TOP_ILLUMINANCE,
                    probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(),
                    lighting.moonPhaseFixedFraction(),
                    options.get(SkyLutPass.SUN_ANGULAR_RADIUS_DEGREES) * TO_RADIANS,
                    options.get(SkyLutPass.MOON_ANGULAR_RADIUS_DEGREES) * TO_RADIANS);
            celestialLights(frame).forEach(sink::submit);
        }
        if (minecraft.player.getItemBySlot(EquipmentSlot.HEAD).is(CausticaItems.SPOTLIGHT_HELMET)) {
            Vec3 eye = minecraft.player.getEyePosition();
            Vector3fc forward = minecraft.gameRenderer.mainCamera().forwardVector();
            double x = eye.x + forward.x() * 0.18;
            double y = eye.y - 0.08 + forward.y() * 0.18;
            double z = eye.z + forward.z() * 0.18;
            sink.submit(new LightDescriptor.Spot(HELMET_LIGHT_KEY, x, y, z,
                    forward.x(), forward.y(), forward.z(),
                    48.0, Math.toRadians(22.0),
                    720.0, 690.0, 610.0));
        }
    }

    static List<LightDescriptor.Distant> celestialLights(CelestialFrame frame) {
        ArrayList<LightDescriptor.Distant> result = new ArrayList<>(2);
        addAboveHorizon(result, SUN_LIGHT_KEY, frame.sunAngleRadians(), frame.noonTiltRadians(),
                frame.sunIlluminanceLux(), frame.sunAngularRadiusRadians());
        double litFraction = Math.abs(frame.moonPhaseIndex() - 4.0) / 4.0;
        double moonIlluminance = frame.moonIlluminanceLux()
                * (frame.moonPhaseFixedFraction()
                        + (1.0 - frame.moonPhaseFixedFraction()) * litFraction);
        addAboveHorizon(result, MOON_LIGHT_KEY, frame.moonAngleRadians(), frame.noonTiltRadians(),
                moonIlluminance, frame.moonAngularRadiusRadians());
        return List.copyOf(result);
    }

    private static void addAboveHorizon(List<LightDescriptor.Distant> result, long key,
                                        double angle, double noonTilt, double illuminance,
                                        double angularRadius) {
        double peak = Math.cos(angle);
        double x = -Math.sin(angle);
        double y = Math.cos(noonTilt) * peak;
        double z = Math.sin(noonTilt) * peak;
        if (y > 0.0 && illuminance > 0.0) {
            result.add(new LightDescriptor.Distant(key, x, y, z,
                    illuminance, illuminance, illuminance, angularRadius));
        }
    }

    record CelestialFrame(double sunAngleRadians, double moonAngleRadians,
                          double noonTiltRadians, double sunIlluminanceLux,
                          double moonIlluminanceLux, int moonPhaseIndex,
                          double moonPhaseFixedFraction, double sunAngularRadiusRadians,
                          double moonAngularRadiusRadians) {
    }
}
