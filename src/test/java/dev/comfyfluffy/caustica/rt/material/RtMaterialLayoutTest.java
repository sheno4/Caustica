package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.rt.gen.MaterialBindingData;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialLayoutTest {
    @Test
    void reflectedWorldPushIncludesOnePackedCameraMediumWord() {
        assertEquals(368, WorldPushData.BYTE_SIZE);
    }

    @Test
    void reflectedMaterialBindingIsOneAlignedLoad() {
        // Sixteen bytes is the point of the record: it is what both any-hit entry points load, and one
        // aligned 128-bit fetch is what keeps that load off the critical path.
        assertEquals(16, MaterialBindingData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(MaterialBindingData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new MaterialBindingData(0x12345678, 9, 0x00C0FFEE, 128).write(data);
        assertEquals(0x12345678, data.getInt(0));
        assertEquals(9, data.getInt(4));
        assertEquals(0x00C0FFEE, data.getInt(8));
        assertEquals(128, data.getInt(12));
    }

    @Test
    void reflectedSurfaceMaterialMatchesClosestHitAbi() {
        assertEquals(64, SurfaceMaterialData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(SurfaceMaterialData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new SurfaceMaterialData(5, 7, 0xC080, 0,
                new Float4(0.01f, 0.02f, 0.03f, 0.04f),
                new Float4(0.05f, 0.06f, 7.0f, 8.0f),
                0.1f, 0.2f, 1.52f, 1.0f).write(data);
        assertEquals(5, data.getInt(0));  // features
        assertEquals(7, data.getInt(4));  // page
        assertEquals(0xC080, data.getInt(8));
        assertEquals("alphaFlags", SurfaceMaterialData.class.getRecordComponents()[3].getName());
        assertEquals(0.01f, data.getFloat(16));
        assertEquals(7.0f, data.getFloat(40));
        assertEquals(0.1f, data.getFloat(48));  // specularRoughness, perceptual
        assertEquals(0.2f, data.getFloat(52));  // baseMetalness
        assertEquals(1.52f, data.getFloat(56)); // specularIor
        assertEquals(1.0f, data.getFloat(60));  // transmissionWeight
        assertEquals("baseColorUv", Arrays.stream(SurfaceMaterialData.class.getRecordComponents())
                .map(component -> component.getName())
                .filter(name -> name.endsWith("ColorUv"))
                .findFirst().orElseThrow());
    }

    @Test
    void canonicalColorBindingsFitTheExistingSurfaceFeatureWord() {
        assertEquals(8, RtMaterialRegistry.FEATURE_SUBSURFACE_COLOR_BASE);
        assertEquals(16, RtMaterialRegistry.FEATURE_EMISSION_COLOR_BASE);
        assertEquals(0, (RtMaterialRegistry.FEATURE_SUBSURFACE_COLOR_BASE
                | RtMaterialRegistry.FEATURE_EMISSION_COLOR_BASE) >>> 8);
        assertEquals(64, SurfaceMaterialData.BYTE_SIZE);
    }

    @Test
    void reflectedWorldPushConstantsIncludeLightBuffersAndFrameIndex() {
        // 10 uint64_t addresses, including the parallel per-instance history table, plus frameIndex.
        assertEquals(88, WorldPushConstantsData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(WorldPushConstantsData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new WorldPushConstantsData(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11).write(data);
        assertEquals(3L, data.getLong(16));  // instanceHistoryAddr
        assertEquals(4L, data.getLong(24));  // materialTableAddr
        assertEquals(5L, data.getLong(32));  // materialSurfaceAddr
        assertEquals(9L, data.getLong(64));  // frameLightNodeAddr
        assertEquals(10L, data.getLong(72)); // pathQueueAddr
        assertEquals(11, data.getInt(80));   // frameIndex
        assertEquals(0, data.getInt(84));    // reflected trailing padding is deterministically zeroed
    }
}
