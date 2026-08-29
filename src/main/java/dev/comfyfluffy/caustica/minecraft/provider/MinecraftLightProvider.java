package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.light.RetainedLightBatch;
import dev.comfyfluffy.caustica.engine.light.RetainedLightSnapshot;
import dev.comfyfluffy.caustica.minecraft.CausticaItems;
import dev.comfyfluffy.caustica.minecraft.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Owns Minecraft's retained celestial, helmet, and emissive-terrain light contributions. */
public final class MinecraftLightProvider implements AutoCloseable {
    private static final double TO_RADIANS = Math.PI / 180.0;
    private static final double SURFACE_TO_TOP_ILLUMINANCE = 100_000.0 / 128_000.0;

    private final LightChannel lights;
    private final SceneId scene;
    private final Supplier<CelestialSettings> celestialSettings;
    private final LightId helmetLight;
    private final LightId sunLight;
    private final LightId moonLight;
    private final Map<Long, TerrainSection> terrainSections = new HashMap<>();
    private long terrainGeneration = Long.MIN_VALUE;

    public MinecraftLightProvider(LightChannel lights, SceneId scene,
                                  Supplier<CelestialSettings> celestialSettings) {
        this.lights = lights;
        this.scene = scene;
        this.celestialSettings = celestialSettings;
        helmetLight = lights.newLight();
        sunLight = lights.newLight();
        moonLight = lights.newLight();
    }

    /** Samples Minecraft state and submits one atomic retained-light update for the current frame. */
    public void update() {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<LightDescriptor.Spot> helmet = Optional.empty();
        CelestialLights celestial = CelestialLights.NONE;
        if (minecraft.player != null) {
            if (minecraft.level != null && Level.OVERWORLD.equals(minecraft.level.dimension())) {
                celestial = celestialLights(celestialFrame(minecraft, celestialSettings.get()));
            }
            if (minecraft.player.getItemBySlot(EquipmentSlot.HEAD).is(CausticaItems.SPOTLIGHT_HELMET)) {
                Vec3 eye = minecraft.player.getEyePosition();
                Vector3fc forward = minecraft.gameRenderer.mainCamera().forwardVector();
                double x = eye.x + forward.x() * 0.18;
                double y = eye.y - 0.08 + forward.y() * 0.18;
                double z = eye.z + forward.z() * 0.18;
                helmet = Optional.of(helmetSpot(x, y, z, forward.x(), forward.y(), forward.z()));
            }
        }
        publish(celestial, helmet, RtTerrain.retainedLightSnapshot());
    }

    void publish(CelestialLights celestial, Optional<LightDescriptor.Spot> helmet,
                 RetainedLightSnapshot terrain) {
        ArrayList<LightChannel.Operation> operations = new ArrayList<>();
        setOrDrop(operations, sunLight, celestial.sun());
        setOrDrop(operations, moonLight, celestial.moon());
        setOrDrop(operations, helmetLight, helmet);
        if (terrain.generation() != terrainGeneration) {
            reconcileTerrain(operations, terrain.batches());
            terrainGeneration = terrain.generation();
        }
        lights.submit(RetainedBatch.of(operations));
    }

    private void reconcileTerrain(List<LightChannel.Operation> operations,
                                  List<RetainedLightBatch> batches) {
        Set<Long> current = new HashSet<>();
        for (RetainedLightBatch batch : batches) {
            current.add(batch.sectionKey());
            TerrainSection previous = terrainSections.get(batch.sectionKey());
            if (previous != null && previous.revision() == batch.revision()) continue;

            ArrayList<LightId> ids = previous == null
                    ? new ArrayList<>()
                    : new ArrayList<>(previous.lights());
            while (ids.size() < batch.lights().size()) ids.add(lights.newLight());
            for (int i = 0; i < batch.lights().size(); i++) {
                operations.add(new LightChannel.SetLight(ids.get(i), scene, batch.lights().get(i)));
            }
            for (int i = batch.lights().size(); i < ids.size(); i++) {
                operations.add(new LightChannel.DropLight(ids.get(i)));
            }
            terrainSections.put(batch.sectionKey(), new TerrainSection(batch.revision(),
                    List.copyOf(ids.subList(0, batch.lights().size()))));
        }
        var iterator = terrainSections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, TerrainSection> entry = iterator.next();
            if (current.contains(entry.getKey())) continue;
            entry.getValue().lights().forEach(light -> operations.add(new LightChannel.DropLight(light)));
            iterator.remove();
        }
    }

    private void setOrDrop(List<LightChannel.Operation> operations, LightId light,
                           Optional<? extends LightDescriptor> descriptor) {
        operations.add(descriptor.<LightChannel.Operation>map(value ->
                new LightChannel.SetLight(light, scene, value)).orElseGet(() ->
                new LightChannel.DropLight(light)));
    }

    @Override
    public void close() {
        ArrayList<LightChannel.Operation> operations = new ArrayList<>();
        operations.add(new LightChannel.DropLight(sunLight));
        operations.add(new LightChannel.DropLight(moonLight));
        operations.add(new LightChannel.DropLight(helmetLight));
        terrainSections.values().forEach(section -> section.lights().forEach(light ->
                operations.add(new LightChannel.DropLight(light))));
        terrainSections.clear();
        lights.submit(RetainedBatch.of(operations));
    }

    static CelestialLights celestialLights(CelestialFrame frame) {
        Optional<LightDescriptor.Distant> sun = aboveHorizon(frame.sunAngleRadians(),
                frame.noonTiltRadians(), frame.sunIlluminanceLux(), frame.sunAngularRadiusRadians());
        double litFraction = Math.abs(frame.moonPhaseIndex() - 4.0) / 4.0;
        double moonIlluminance = frame.moonIlluminanceLux()
                * (frame.moonPhaseFixedFraction()
                + (1.0 - frame.moonPhaseFixedFraction()) * litFraction);
        Optional<LightDescriptor.Distant> moon = aboveHorizon(frame.moonAngleRadians(),
                frame.noonTiltRadians(), moonIlluminance, frame.moonAngularRadiusRadians());
        return new CelestialLights(sun, moon);
    }

    private static CelestialFrame celestialFrame(Minecraft minecraft, CelestialSettings settings) {
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
        MinecraftLightingCalibration lighting = MinecraftLightingCalibration.current();
        return new CelestialFrame(
                probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * TO_RADIANS,
                probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * TO_RADIANS,
                settings.noonTiltDegrees() * TO_RADIANS,
                lighting.sunIlluminanceLux() * SURFACE_TO_TOP_ILLUMINANCE,
                lighting.moonIlluminanceLux() * SURFACE_TO_TOP_ILLUMINANCE,
                probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(),
                lighting.moonPhaseFixedFraction(),
                settings.sunAngularRadiusDegrees() * TO_RADIANS,
                settings.moonAngularRadiusDegrees() * TO_RADIANS);
    }

    private static Optional<LightDescriptor.Distant> aboveHorizon(double angle, double noonTilt,
                                                                   double illuminance,
                                                                   double angularRadius) {
        double peak = Math.cos(angle);
        double x = -Math.sin(angle);
        double y = Math.cos(noonTilt) * peak;
        double z = Math.sin(noonTilt) * peak;
        if (y <= 0.0 || illuminance <= 0.0) return Optional.empty();
        return Optional.of(new LightDescriptor.Distant(x, y, z,
                illuminance, illuminance, illuminance, angularRadius, true));
    }

    static LightDescriptor.Spot helmetSpot(double x, double y, double z,
                                           double directionX, double directionY, double directionZ) {
        return new LightDescriptor.Spot(x, y, z, directionX, directionY, directionZ,
                48.0, Math.toRadians(22.0), 720.0, 690.0, 610.0);
    }

    record CelestialLights(Optional<LightDescriptor.Distant> sun,
                           Optional<LightDescriptor.Distant> moon) {
        private static final CelestialLights NONE = new CelestialLights(Optional.empty(), Optional.empty());
    }

    record CelestialFrame(double sunAngleRadians, double moonAngleRadians,
                          double noonTiltRadians, double sunIlluminanceLux,
                          double moonIlluminanceLux, int moonPhaseIndex,
                          double moonPhaseFixedFraction, double sunAngularRadiusRadians,
                          double moonAngularRadiusRadians) {
    }

    /** Sky-owned angular settings sampled by this contribution without process-global option access. */
    public record CelestialSettings(double noonTiltDegrees, double sunAngularRadiusDegrees,
                                    double moonAngularRadiusDegrees) {
    }

    private record TerrainSection(long revision, List<LightId> lights) {
    }
}
