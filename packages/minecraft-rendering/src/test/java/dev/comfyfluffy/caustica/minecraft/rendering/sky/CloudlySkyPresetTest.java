package dev.comfyfluffy.caustica.minecraft.rendering.sky;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.rendering.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.gen.SkyInputsData;
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
        assertEquals(-Math.sin(CAPTURED.sunAngleRadians()), sun.directionX(), 1e-12);
        assertEquals(Math.cos(Math.toRadians(30)) * Math.cos(CAPTURED.sunAngleRadians()), sun.directionY(), 1e-12);
        assertEquals(Math.sin(Math.toRadians(30)) * Math.cos(CAPTURED.sunAngleRadians()), sun.directionZ(), 1e-12);
        assertTrue(selected.sunAngleRadians().isEmpty());
        assertEquals(sun.directionX(), -Math.sin(sky.sunAngleRadians()), 1e-7);
        assertEquals(sun.directionY(), Math.cos(sky.noonTiltRadians()) * Math.cos(sky.sunAngleRadians()), 1e-7);
        assertEquals(sun.directionZ(), Math.sin(sky.noonTiltRadians()) * Math.cos(sky.sunAngleRadians()), 1e-7);
        assertEquals(100_000, sun.illuminanceRedLux(), 1e-9);
        assertEquals(100_000, sky.sunIlluminanceLux());
        assertEquals(.535700023 * .5, selected.sunAngularRadiusDegrees(), 1e-12);
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

    @Test void sceneSunAndSerializedOverridesRetainNativeDefaultsAndDiscEnergy(@TempDir Path directory) throws Exception {
        Path path = source(directory, 25, -72, "sourceX").manifest();
        JsonObject manifest = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        manifest.getAsJsonObject("rendererAdapter").addProperty("sunDirectionSource", "sceneDirectionalLight");
        manifest.getAsJsonObject("rendererAdapter").remove("sunAzimuthAxis");
        manifest.add("sceneParameters", JsonParser.parseString("""
                {"directionalLight":{"RelativeRotation":{"Pitch":-25,"Yaw":18,"Roll":0}}}
                """));
        JsonObject skyParameters = manifest.getAsJsonObject("skyParameters");
        skyParameters.add("SunIntensity[115]", JsonParser.parseString("""
                {"Factor":120000,"RGB":{"X":0.8}}
                """));
        skyParameters.addProperty("SunDiskVisibleAngleInDegrees[119]", .7);
        Files.writeString(path, manifest.toString());

        var pack = CloudlySourcePack.load(path);
        var preset = CloudlySkyPreset.from(pack);
        var selected = preset.lightSettings(new MinecraftLightProvider.CelestialSettings(30, .6, 1.5));
        var sun = MinecraftLightProvider.celestialLights(CAPTURED, selected).sun().orElseThrow();
        var sky = SkyLutPass.gather(IMPORT_ENABLED, CAPTURED, preset);
        var inputs = SkyLutPass.skyInputs(sky, new SkyLutPass.AtlasSnapshot(null, 0, 1,
                new SkyInputsData.Float4(0, 0, 1, 1), new SkyInputsData.Float4(0, 0, 1, 1)));

        assertEquals(-Math.cos(Math.toRadians(25)) * Math.cos(Math.toRadians(18)), -Math.sin(preset.sunAngleRadians()), 1e-12);
        assertEquals(-Math.sin(CAPTURED.sunAngleRadians()), sun.directionX(), 1e-12);
        assertEquals(Math.cos(Math.toRadians(30)) * Math.cos(CAPTURED.sunAngleRadians()), sun.directionY(), 1e-12);
        assertEquals(Math.sin(Math.toRadians(30)) * Math.cos(CAPTURED.sunAngleRadians()), sun.directionZ(), 1e-12);
        assertEquals(120_000, sky.sunIlluminanceLux());
        assertEquals(.8, preset.sunColorLinearBt709().red());
        assertEquals(1, preset.sunColorLinearBt709().green());
        assertEquals(1, preset.sunColorLinearBt709().blue());
        assertEquals(120_000 * preset.sunColorAcesCg()[0], sun.illuminanceRedLux(), .01);
        assertEquals(Math.toRadians(.535700023 * .5), preset.sunAngularRadiusRadians(), 1e-12);
        assertEquals(Math.toRadians(.7 * .5), preset.sunDiscHalfAngleRadians(), 1e-12);
        assertEquals(1, inputs.skyLook3().z());
        assertEquals(.8f, inputs.sunColor().x());
        assertFalse(pack.skyParameters().containsKey("SunDiskAngleInDegrees"));

        double angle = preset.sunDiscHalfAngleRadians();
        double step = angle / 4096;
        double projectedIntegral = 0;
        for (int sample = 0; sample < 4096; sample++) {
            double theta = (sample + .5) * step;
            projectedIntegral += 2 * Math.PI * Math.sin(theta) * Math.cos(theta) * step;
        }
        assertEquals(preset.sunIlluminanceLux(),
                CloudlySkyPreset.discRadiance(preset.sunIlluminanceLux(), angle) * projectedIntegral, .01);
    }

    @Test void importedSunMovesWithWorldTimeAndUsesConfiguredTilt(@TempDir Path directory) throws Exception {
        var preset = CloudlySkyPreset.from(source(directory, 30, 90, "sourceX"));
        OptionValues configured = new OptionValues() {
            @Override public <T> T get(Option<T> option) {
                if (option.equals(CloudlyCloudPass.ENABLED)) return option.normalize(true);
                if (option.equals(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES)) return option.normalize(50f);
                return option.defaultValue();
            }
        };
        var settings = preset.lightSettings(new MinecraftLightProvider.CelestialSettings(50, .6, 1.5));
        var morning = new MinecraftCelestialFrame(-.8f, 0, 0, 0, 0, 63, 80, 1, LIGHTING);
        var noon = new MinecraftCelestialFrame(0, 0, 0, 0, 0, 63, 80, 1, LIGHTING);
        var night = new MinecraftCelestialFrame((float)Math.PI, 0, 0, 0, 0, 63, 80, 1, LIGHTING);
        var morningSun = MinecraftLightProvider.celestialLights(morning, settings).sun().orElseThrow();
        var noonSun = MinecraftLightProvider.celestialLights(noon, settings).sun().orElseThrow();
        var morningSky = SkyLutPass.gather(configured, morning, preset);
        var noonSky = SkyLutPass.gather(configured, noon, preset);
        assertNotEquals(morningSun.directionX(), noonSun.directionX());
        assertEquals(-Math.sin(morningSky.sunAngleRadians()), morningSun.directionX(), 1e-7);
        assertEquals(Math.cos(noonSky.noonTiltRadians()), noonSun.directionY(), 1e-7);
        assertEquals(Math.toRadians(50), noonSky.noonTiltRadians(), 1e-7);
        assertEquals(100000, noonSun.illuminanceRedLux(), 1e-9);
        assertTrue(MinecraftLightProvider.celestialLights(night, settings).sun().isEmpty());
        assertEquals(night.sunAngleRadians(), SkyLutPass.gather(configured, night, preset).sunAngleRadians());
    }

    @Test void nativeMoonEnergyFollowsCapturedPhaseAndDisplaySizeStaysIndependent(@TempDir Path directory) throws Exception {
        var preset = CloudlySkyPreset.from(source(directory, 30, 90, "sourceX"));
        OptionValues sizes = new OptionValues() {
            @Override public <T> T get(Option<T> option) {
                if (option.equals(CloudlyCloudPass.ENABLED)) return option.normalize(true);
                if (option.equals(SkyLutPass.SUN_DISC_HALF_ANGLE_DEGREES)) return option.normalize(2f);
                if (option.equals(SkyLutPass.MOON_DISC_HALF_ANGLE_DEGREES)) return option.normalize(3f);
                return option.defaultValue();
            }
        };
        var settings = preset.lightSettings(new MinecraftLightProvider.CelestialSettings(30, .6, 1.5));
        var full = new MinecraftCelestialFrame((float)Math.PI, 0, 0, 1, 0, 63, 80, 1, LIGHTING);
        var newMoon = new MinecraftCelestialFrame((float)Math.PI, 0, 0, 1, 4, 63, 80, 1, LIGHTING);
        var fullLights = MinecraftLightProvider.celestialLights(full, settings);
        var newLights = MinecraftLightProvider.celestialLights(newMoon, settings);
        var sky = SkyLutPass.gather(sizes, full, preset);
        assertTrue(fullLights.sun().isEmpty());
        assertEquals(.25, fullLights.moon().orElseThrow().illuminanceRedLux(), 1e-7);
        assertEquals(.025, newLights.moon().orElseThrow().illuminanceRedLux(), 1e-7);
        assertEquals(preset.moonIlluminanceLux(), sky.moonIlluminanceLux(), 1e-7);
        assertEquals(Math.toRadians(2), sky.sunDiscHalfAngleRadians(), 1e-7);
        assertEquals(Math.toRadians(3), sky.moonDiscHalfAngleRadians(), 1e-7);
        assertEquals(preset.sunAngularRadiusRadians(), sky.sunAngularRadiusRadians(), 1e-7);
    }

    private static CloudlySourcePack source(Path directory, double elevation, double azimuth, String axis) throws Exception {
        Files.write(directory.resolve("mip.bc1"), new byte[8]);
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, """
                {"schemaVersion":1,"source":{},"skyParameters":{
                 "SunElevationAngleInDegrees[108]":%s,"SunAzimuthAngleInDegrees[109]":%s},
                 "nativeDefaults":{"source":{},"skyParameters":{"bUseUnrealSunData":true,
                  "SunIntensity":{"Factor":100000,"RGB":{"X":1,"Y":1,"Z":1}},
                  "MoonFractionOfSunIntensity":0.0000025,
                  "SunDiskAngleInDegrees":0.535700023,"SunDiskVisibleAngleInDegrees":0.535700023}},
                 "components":[],"rendererAdapter":{"target":"Caustica","approximation":true,"sunAzimuthAxis":"%s",
                   "sunAngleMeaning":"fullDiameter","sunIntensityUnits":"lux","sunColorSpace":"linearBt709"},
                 "textures":[{"textureId":0,"sourceAsset":"fixture","format":"PF_DXT1",
                   "mips":[{"level":0,"width":4,"height":4,"depth":1,"path":"mip.bc1","sha256":"%s"}]}]}
                """.formatted(elevation, azimuth, axis, "0".repeat(64)));
        return CloudlySourcePack.load(manifest);
    }
}
