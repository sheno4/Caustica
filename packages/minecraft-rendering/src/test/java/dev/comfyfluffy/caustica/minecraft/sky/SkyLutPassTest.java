package dev.comfyfluffy.caustica.minecraft.sky;

import dev.comfyfluffy.caustica.minecraft.sky.gen.MinecraftEnvironmentBindingData;
import dev.comfyfluffy.caustica.minecraft.sky.gen.SkyInputsData;
import dev.comfyfluffy.caustica.minecraft.sky.gen.SkyLutPushData;
import dev.comfyfluffy.caustica.minecraft.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkyLutPassTest {
    private static final SkyLutPass.SkyState SKY = new SkyLutPass.SkyState(
            .1f, .2f, .3f, .4f, 100_000, .2f, .003f, 1.5f, .5f, .01f, .02f,
            .1f, .03f, .04f, 1.25f, 2, .3f, .05f);
    private static final SkyLutPass.AtlasSnapshot ATLAS = new SkyLutPass.AtlasSnapshot(null, 0, 1,
            new SkyInputsData.Float4(.1f, .2f, .3f, .4f),
            new SkyInputsData.Float4(.5f, .6f, .7f, .8f));

    @Test void generatedSkyInputsMapStateAndAtlasRects() {
        ByteBuffer bytes = ByteBuffer.allocate(SkyInputsData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        SkyLutPass.skyInputs(SKY, ATLAS).write(bytes);
        assertEquals(112, SkyInputsData.BYTE_SIZE);
        assertEquals(SKY.sunAngleRadians(), bytes.getFloat(0));
        assertEquals(SKY.groundAlbedo(), bytes.getFloat(64));
        assertEquals(.1f, bytes.getFloat(80));
        assertEquals(.8f, bytes.getFloat(108));
    }

    @Test void generatedRootsMatchDescriptorHeapAbi() {
        assertEquals(128, SkyLutPushData.BYTE_SIZE);
        assertEquals(32, MinecraftEnvironmentBindingData.BYTE_SIZE);
        assertEquals(1, SkyLutPass.groups(1));
        assertEquals(2, SkyLutPass.groups(9));
    }

    @Test void lutDimensionsMatchAtmosphereConstants() {
        assertEquals(256, SkyLutPass.TRANSMITTANCE_WIDTH);
        assertEquals(64, SkyLutPass.TRANSMITTANCE_HEIGHT);
        assertEquals(192, SkyLutPass.SKY_VIEW_WIDTH);
        assertEquals(216, SkyLutPass.SKY_VIEW_HEIGHT);
    }

    @Test void convertsSceneAltitudeToKilometresUsingFrameScale() {
        assertEquals(1.0f, SkyLutPass.viewerAltitudeKm(1063.0, 63.0, 1.0));
        assertEquals(1.0f, SkyLutPass.viewerAltitudeKm(2063.0, 63.0, 0.5));
        assertEquals(0.0f, SkyLutPass.viewerAltitudeKm(20.0, 63.0, 1.0));
    }

    @Test void dimensionCatalogSelectsOnlyTheBuiltInOverworldSky() {
        MinecraftSkyCatalog catalog = new MinecraftSkyCatalog();
        assertTrue(catalog.supports(new MinecraftDimensionKey(ResourceId.of("minecraft", "overworld"))));
        assertFalse(catalog.supports(new MinecraftDimensionKey(ResourceId.of("minecraft", "the_nether"))));
    }

    @Test void skyStateUsesOneCapturedHostFrame() {
        var lighting = new MinecraftLightingCalibration(100, 2, 3, 4, 5, .25f);
        var captured = new MinecraftCelestialFrame(.1f, .2f, .3f, .4f,
                6, 63, 1063, 1, lighting);
        OptionValues defaults = new OptionValues() {
            @Override public <T> T get(Option<T> option) { return option.defaultValue(); }
        };

        SkyLutPass.SkyState state = SkyLutPass.gather(defaults, captured);

        assertEquals(.1f, state.sunAngleRadians());
        assertEquals(.3f, state.starAngleRadians());
        assertEquals(1f, state.viewerAltitudeKm());
        assertEquals(6f, state.moonPhaseIndex());
    }
}
