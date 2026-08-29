package dev.comfyfluffy.caustica.minecraft.sky;

import dev.comfyfluffy.caustica.minecraft.sky.gen.MinecraftEnvironmentBindingData;
import dev.comfyfluffy.caustica.minecraft.sky.gen.SkyInputsData;
import dev.comfyfluffy.caustica.minecraft.sky.gen.SkyLutPushData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
