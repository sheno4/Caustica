package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.minecraft.rendering.provider.MinecraftLightProvider;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/** Sun placement for the explicit source-X azimuth adapter, retaining the host's photometric calibration. */
public record CloudlySkyPreset(double sunAngleRadians, double noonTiltDegrees) {
    public static CloudlySkyPreset from(CloudlySourcePack source) {
        if (!"sourceX".equals(source.rendererAdapter().get("sunAzimuthAxis"))) {
            throw new IllegalArgumentException("Cloudly sun import requires rendererAdapter.sunAzimuthAxis=sourceX");
        }
        double elevation = requiredNumber(source.skyParameters(), "SunElevationAngleInDegrees");
        double azimuth = requiredNumber(source.skyParameters(), "SunAzimuthAngleInDegrees");
        if (elevation < -90 || elevation > 90) {
            throw new IllegalArgumentException("Cloudly sun elevation is outside -90..90 degrees");
        }
        double e = Math.toRadians(elevation);
        double a = Math.toRadians(azimuth);
        // The declared adapter maps the source's Z-up direction to host (X, Z, -Y).
        double x = Math.cos(e) * Math.cos(a);
        double y = Math.sin(e);
        double z = -Math.cos(e) * Math.sin(a);
        return new CloudlySkyPreset(-Math.asin(x), Math.toDegrees(Math.atan2(z, y)));
    }

    public MinecraftLightProvider.CelestialSettings lightSettings(MinecraftLightProvider.CelestialSettings base) {
        return new MinecraftLightProvider.CelestialSettings(noonTiltDegrees,
                base.sunAngularRadiusDegrees(), base.moonAngularRadiusDegrees(),
                OptionalDouble.of(sunAngleRadians));
    }

    private static double requiredNumber(Map<String, Object> parameters, String name) {
        Pattern indexed = Pattern.compile(Pattern.quote(name) + "\\[\\d+\\]");
        var matches = parameters.entrySet().stream()
                .filter(entry -> entry.getKey().equals(name) || indexed.matcher(entry.getKey()).matches()).toList();
        if (matches.size() != 1 || !(matches.getFirst().getValue() instanceof Number number)
                || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Cloudly sun import requires one finite serialized " + name);
        }
        return number.doubleValue();
    }
}
