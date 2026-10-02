package dev.comfyfluffy.caustica.minecraft.rendering.sky;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.rendering.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.CloudlySkyPreset;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.CloudlyCloudPass;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.CloudlySourcePack;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class CloudlySkyPresetTest {
    private static final OptionValues DEFAULT_OPTIONS = new OptionValues() {
        @Override public <T> T get(Option<T> option) { return option.defaultValue(); }
    };
    private static final OptionValues IMPORT_ENABLED = new OptionValues() {
        @Override public <T> T get(Option<T> option) {
            return option.equals(CloudlyCloudPass.ENABLED) ? option.normalize(true) : option.defaultValue();
        }
    };
    private static final MinecraftLightingCalibration LIGHTING =
            new MinecraftLightingCalibration(128_000, 5, 1, .003f, 1.5f, .1f);
    private static final MinecraftCelestialFrame CAPTURED =
            new MinecraftCelestialFrame(.42f, (float) Math.PI, .2f, .3f,
                    2, 63, 80, 1, LIGHTING);

    @Test void importedSunMatchesRetainedLightingAndSkyInputs(@TempDir Path directory) throws Exception {
        var preset = CloudlySkyPreset.from(source(directory, 30, 90, "sourceX"));
        var base = new MinecraftLightProvider.CelestialSettings(30, .6, 1.5);
        var selected = preset.lightSettings(base);
        var sun = MinecraftLightProvider.celestialLights(CAPTURED, selected).sun().orElseThrow();
        var sky = SkyLutPass.gather(IMPORT_ENABLED, CAPTURED, preset);
        assertEquals(0, sun.directionX(), 1e-12);
        assertEquals(.5, sun.directionY(), 1e-12);
        assertEquals(-Math.sqrt(3) / 2, sun.directionZ(), 1e-12);
        assertEquals(sun.directionX(), -Math.sin(sky.sunAngleRadians()), 1e-7);
        assertEquals(sun.directionY(), Math.cos(sky.noonTiltRadians()) * Math.cos(sky.sunAngleRadians()), 1e-7);
        assertEquals(sun.directionZ(), Math.sin(sky.noonTiltRadians()) * Math.cos(sky.sunAngleRadians()), 1e-7);
        assertEquals(100_000, sun.illuminanceRedLux(), 1e-9);
        assertEquals(LIGHTING.sunIlluminanceLux(), sky.sunIlluminanceLux());
        assertEquals(base.sunAngularRadiusDegrees(), selected.sunAngularRadiusDegrees());
        assertEquals(base.moonAngularRadiusDegrees(), selected.moonAngularRadiusDegrees());
        assertEquals(CAPTURED.moonAngleRadians(), sky.moonAngleRadians());
        assertEquals(CAPTURED.starAngleRadians(), sky.starAngleRadians());
    }

    @Test void absentPresetKeepsCapturedMinecraftSun() {
        var settings = new MinecraftLightProvider.CelestialSettings(30, .6, 1.5);
        assertTrue(settings.sunAngleRadians().isEmpty());
        var sky = SkyLutPass.gather(DEFAULT_OPTIONS, CAPTURED);
        var sun = MinecraftLightProvider.celestialLights(CAPTURED, settings).sun().orElseThrow();
        assertEquals(CAPTURED.sunAngleRadians(), sky.sunAngleRadians());
        assertEquals(-Math.sin(CAPTURED.sunAngleRadians()), sun.directionX(), 1e-12);
    }

    @Test void disablingImportRestoresCapturedSunAndConfiguredTilt(@TempDir Path directory) throws Exception {
        var preset = CloudlySkyPreset.from(source(directory, 30, 90, "sourceX"));
        var disabled = SkyLutPass.gather(DEFAULT_OPTIONS, CAPTURED, preset);
        assertEquals(CAPTURED.sunAngleRadians(), disabled.sunAngleRadians());
        assertEquals(Math.toRadians(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES.defaultValue()),
                disabled.noonTiltRadians(), 1e-7);
    }

    @Test void sourceAxisAndSerializedAnglesAreRequired(@TempDir Path directory) throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> CloudlySkyPreset.from(source(directory, 30, 90, "sourceY")));
        assertThrows(IllegalArgumentException.class,
                () -> CloudlySkyPreset.from(source(directory, 100, 90, "sourceX")));
        Path manifest = source(directory, 30, 90, "sourceX").manifest();
        Files.writeString(manifest, Files.readString(manifest).replace("SunAzimuthAngleInDegrees[109]", "MissingAzimuth"));
        assertThrows(IllegalArgumentException.class, () -> CloudlySkyPreset.from(CloudlySourcePack.load(manifest)));
    }

    private static CloudlySourcePack source(Path directory, double elevation, double azimuth, String axis) throws Exception {
        Files.write(directory.resolve("mip.bc1"), new byte[8]);
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, """
                {"schemaVersion":1,"source":{},"skyParameters":{
                 "SunElevationAngleInDegrees[108]":%s,"SunAzimuthAngleInDegrees[109]":%s},
                 "components":[],"rendererAdapter":{"target":"Caustica","approximation":true,"sunAzimuthAxis":"%s"},
                 "textures":[{"textureId":0,"sourceAsset":"fixture","format":"PF_DXT1",
                   "mips":[{"level":0,"width":4,"height":4,"depth":1,"path":"mip.bc1","sha256":"%s"}]}]}
                """.formatted(elevation, azimuth, axis, "0".repeat(64)));
        return CloudlySourcePack.load(manifest);
    }
}
