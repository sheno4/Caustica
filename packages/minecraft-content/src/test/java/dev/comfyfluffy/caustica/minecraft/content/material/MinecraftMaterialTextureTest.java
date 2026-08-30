package dev.comfyfluffy.caustica.minecraft.content.material;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftMaterialTextureTest {
    @Test
    void ownsMipPayloadsAndDoesNotExposeMutableArrays() {
        byte[] source = {1, 2, 3, 4};
        MinecraftMaterialTexture texture = new MinecraftMaterialTexture(List.of(
                new MinecraftMaterialTexture.Mip(1, 1, source)));
        source[0] = 99;
        byte[] borrowed = texture.levels().getFirst().rgba8();
        borrowed[1] = 88;

        assertArrayEquals(new byte[]{1, 2, 3, 4}, texture.levels().getFirst().rgba8());
    }

    @Test
    void requiresAConsistentHalvingMipChain() {
        var base = new MinecraftMaterialTexture.Mip(4, 4, new byte[4 * 4 * 4]);
        var invalid = new MinecraftMaterialTexture.Mip(3, 2, new byte[3 * 2 * 4]);

        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftMaterialTexture(List.of(base, invalid)));
        assertEquals(4, base.width());
    }
}
