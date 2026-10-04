package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.minecraft.rendering.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.support.ColorSpaces;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Source sun values under the pack's explicit direction, angle, photometric and colour-space adapter. */
public record CloudlySkyPreset(double sunAngleRadians, double noonTiltDegrees, Sunlight sunlight) {
    public static CloudlySkyPreset from(CloudlySourcePack source) {
        require(source.rendererAdapter(), "sunAngleMeaning", "fullDiameter");
        require(source.rendererAdapter(), "sunIntensityUnits", "lux");
        require(source.rendererAdapter(), "sunColorSpace", "linearBt709");
        double x, y, z;
        if ("sceneDirectionalLight".equals(source.rendererAdapter().get("sunDirectionSource"))) {
            Object unrealSun = property(source.skyParameters(), "bUseUnrealSunData");
            if (unrealSun == null) unrealSun = property(nativeSky(source), "bUseUnrealSunData");
            if (!Boolean.TRUE.equals(unrealSun)) {
                throw new IllegalArgumentException("Scene sun import requires source bUseUnrealSunData=true");
            }
            Map<String, Object> light = object(property(source.sceneParameters(), "directionalLight"));
            Map<String, Object> rotation = object(property(light, "RelativeRotation"));
            double pitch = Math.toRadians(resolvedNumber(rotation, Map.of(), "Pitch"));
            double yaw = Math.toRadians(resolvedNumber(rotation, Map.of(), "Yaw"));
            // Unreal's directional light points away from the sun; source Z-up maps to host (X,Z,-Y).
            x = -Math.cos(pitch) * Math.cos(yaw);
            y = -Math.sin(pitch);
            z = Math.cos(pitch) * Math.sin(yaw);
        } else {
            require(source.rendererAdapter(), "sunAzimuthAxis", "sourceX");
            double elevation = resolvedNumber(source, "SunElevationAngleInDegrees");
            double azimuth = resolvedNumber(source, "SunAzimuthAngleInDegrees");
            if (elevation < -90 || elevation > 90) {
                throw new IllegalArgumentException("Cloudly sun elevation is outside -90..90 degrees");
            }
            double e = Math.toRadians(elevation);
            double a = Math.toRadians(azimuth);
            x = Math.cos(e) * Math.cos(a);
            y = Math.sin(e);
            z = -Math.cos(e) * Math.sin(a);
        }
        Map<String, Object> intensity = object(property(source.skyParameters(), "SunIntensity"));
        Map<String, Object> intensityDefaults = object(property(nativeSky(source), "SunIntensity"));
        double illuminance = resolvedNumber(intensity, intensityDefaults, "Factor");
        Map<String, Object> rgb = object(property(intensity, "RGB"));
        Map<String, Object> rgbDefaults = object(property(intensityDefaults, "RGB"));
        Color color = new Color(resolvedNumber(rgb, rgbDefaults, "X"),
                resolvedNumber(rgb, rgbDefaults, "Y"), resolvedNumber(rgb, rgbDefaults, "Z"));
        double physicalRadius = Math.toRadians(resolvedNumber(source, "SunDiskAngleInDegrees") * .5);
        double visibleRadius = Math.toRadians(resolvedNumber(source, "SunDiskVisibleAngleInDegrees") * .5);
        if (illuminance < 0 || color.red() < 0 || color.green() < 0 || color.blue() < 0
                || physicalRadius <= 0 || physicalRadius >= Math.PI / 2
                || visibleRadius <= 0 || visibleRadius >= Math.PI / 2) {
            throw new IllegalArgumentException("Cloudly sun radiance and angular diameters must be physically valid");
        }
        return new CloudlySkyPreset(-Math.asin(x), Math.toDegrees(Math.atan2(z, y)),
                new Sunlight(illuminance, physicalRadius, visibleRadius, color));
    }

    public MinecraftLightProvider.CelestialSettings lightSettings(MinecraftLightProvider.CelestialSettings base) {
        float[] color = sunColorAcesCg();
        return new MinecraftLightProvider.CelestialSettings(base.noonTiltDegrees(),
                Math.toDegrees(sunAngularRadiusRadians()), base.moonAngularRadiusDegrees(),
                base.sunAngleRadians(), Optional.of(new MinecraftLightProvider.SunLightOverride(
                        sunIlluminanceLux(), color[0], color[1], color[2])));
    }

    public double sunIlluminanceLux() { return sunlight.illuminanceLux(); }
    public double sunAngularRadiusRadians() { return sunlight.angularRadiusRadians(); }
    public double sunDiscHalfAngleRadians() { return sunlight.discHalfAngleRadians(); }
    public Color sunColorLinearBt709() { return sunlight.colorLinearBt709(); }
    public float[] sunColorAcesCg() {
        Color color = sunColorLinearBt709();
        return ColorSpaces.linearBt709ToAcesCg(color.red(), color.green(), color.blue());
    }

    /** The diameter and lux interpretations are declared by rendererAdapter, separate from native defaults. */
    public record Sunlight(double illuminanceLux, double angularRadiusRadians,
                           double discHalfAngleRadians, Color colorLinearBt709) { }
    public record Color(double red, double green, double blue) { }

    public static double discRadiance(double illuminanceLux, double halfAngleRadians) {
        double sine = Math.sin(halfAngleRadians);
        return illuminanceLux / (Math.PI * sine * sine);
    }

    private static double resolvedNumber(CloudlySourcePack source, String name) {
        return resolvedNumber(source.skyParameters(), nativeSky(source), name);
    }

    private static Map<String, Object> nativeSky(CloudlySourcePack source) {
        return object(property(source.nativeDefaults(), "skyParameters"));
    }

    private static double resolvedNumber(Map<String, Object> serialized, Map<String, Object> defaults, String name) {
        Object value = property(serialized, name);
        if (value == null) value = property(defaults, name);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Cloudly sun import requires finite source or native-default " + name);
        }
        return number.doubleValue();
    }

    private static Object property(Map<String, Object> parameters, String name) {
        Pattern indexed = Pattern.compile(Pattern.quote(name) + "\\[\\d+\\]");
        var matches = parameters.entrySet().stream()
                .filter(entry -> entry.getKey().equals(name) || indexed.matcher(entry.getKey()).matches()).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous source sun property " + name);
        return matches.isEmpty() ? null : matches.getFirst().getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Source sun field must be an object");
        return (Map<String, Object>) value;
    }

    private static void require(Map<String, Object> adapter, String name, String value) {
        if (!value.equals(adapter.get(name))) {
            throw new IllegalArgumentException("Cloudly sun import requires rendererAdapter." + name + "=" + value);
        }
    }
}
