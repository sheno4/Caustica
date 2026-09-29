package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

final class CloudResourcesTest {
    private static InputStream resource(String name) {
        InputStream stream = CloudResourcesTest.class.getResourceAsStream("/caustica/clouds/" + name);
        assertNotNull(stream, name);
        return stream;
    }

    @Test
    void noiseTablesMatchShaderAddressRanges() throws IOException {
        String[] names = {"cumulus_base.bin", "cumulus_detail1.bin", "cumulus_detail2.bin", "cumulus_curl.bin"};
        int[] lengths = {1024*1024,128*128*128,128*128*128,64*64*64*4};
        int total = 0;
        for (int i=0;i<names.length;i++) {
            try (InputStream stream = resource(names[i])) {
                byte[] bytes = stream.readAllBytes();
                assertEquals(lengths[i],bytes.length,names[i]);
                total += bytes.length;
            }
        }
        assertEquals(6291456,total,"cirrus buffer offset");
    }

    @Test
    void cirrusHasExpectedUnormLayout() throws IOException {
        // Decode metadata and sample layout only; no visual or image-recognition assertions.
        try (InputStream stream = resource("cirrus_20.png")) {
            var image = ImageIO.read(stream);
            assertNotNull(image);
            assertEquals(256,image.getWidth());
            assertEquals(256,image.getHeight());
            assertEquals(8,image.getSampleModel().getSampleSize(0));
        }
    }

    @Test
    void phaseTableContainsFiniteNonnegativeHalfFloats() throws IOException {
        try (InputStream stream = resource("opac_cloud_phases.bin")) {
            byte[] data = stream.readAllBytes();
            assertEquals(256*3*4*2,data.length);
            ByteBuffer values = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            while (values.hasRemaining()) {
                float value = Float.float16ToFloat(values.getShort());
                assertTrue(Float.isFinite(value) && value >= 0.0f,"invalid phase coefficient");
            }
        }
    }
}
