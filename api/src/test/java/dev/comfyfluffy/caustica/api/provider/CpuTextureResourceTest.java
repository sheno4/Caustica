package dev.comfyfluffy.caustica.api.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CpuTextureResourceTest {
    @Test
    void ownsEveryMipLevelAndKeepsTheSingleLevelConstructor() {
        byte[] base = new byte[8 * 4 * 4];
        byte[] mip = new byte[4 * 2 * 4];
        base[0] = 3;
        mip[0] = 7;
        CpuTextureResource texture = new CpuTextureResource(CpuTextureResource.Encoding.LINEAR, List.of(
                new CpuTextureResource.MipLevel(8, 4, base),
                new CpuTextureResource.MipLevel(4, 2, mip),
                new CpuTextureResource.MipLevel(2, 1, new byte[8])));

        base[0] = 9;
        mip[0] = 9;
        assertEquals(3, texture.mipLevels().size());
        assertEquals(8, texture.width());
        assertEquals(4, texture.height());
        assertEquals(3, texture.rgba8()[0]);
        assertEquals(7, texture.mipLevels().get(1).rgba8()[0]);

        byte[] returned = texture.mipLevels().getFirst().rgba8();
        returned[0] = 11;
        assertEquals(3, texture.rgba8()[0]);

        CpuTextureResource single = new CpuTextureResource(2, 2, CpuTextureResource.Encoding.SRGB,
                new byte[16]);
        assertEquals(1, single.mipLevels().size());
        assertArrayEquals(new byte[16], single.rgba8());
    }

    @Test
    void rejectsMissingOrDiscontinuousMipLevels() {
        assertThrows(IllegalArgumentException.class, () ->
                new CpuTextureResource(CpuTextureResource.Encoding.SRGB, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new CpuTextureResource(CpuTextureResource.Encoding.SRGB, List.of(
                        new CpuTextureResource.MipLevel(8, 8, new byte[256]),
                        new CpuTextureResource.MipLevel(3, 4, new byte[48]))));
        assertThrows(IllegalArgumentException.class, () ->
                new CpuTextureResource(CpuTextureResource.Encoding.SRGB, List.of(
                        new CpuTextureResource.MipLevel(1, 1, new byte[4]),
                        new CpuTextureResource.MipLevel(1, 1, new byte[4]))));
    }
}
