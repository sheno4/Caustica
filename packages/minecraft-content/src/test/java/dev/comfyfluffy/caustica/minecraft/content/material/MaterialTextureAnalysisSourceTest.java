package dev.comfyfluffy.caustica.minecraft.content.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MaterialTextureAnalysisSourceTest {
    @Test
    void ownsCompileDimensionsAndEpochAlphaCardinality() {
        MaterialTextureSource texture = () -> null;
        MaterialTextureAnalysisSource source = new MaterialTextureAnalysisSource(32, 16, 4, texture);

        assertEquals(32, source.width());
        assertEquals(16, source.height());
        assertEquals(4, source.alphaFrameCount());
        assertSame(texture, source.texture());
    }

    @Test
    void rejectsInvalidAnalysisShape() {
        MaterialTextureSource texture = () -> null;
        assertThrows(IllegalArgumentException.class,
                () -> new MaterialTextureAnalysisSource(0, 1, 1, texture));
        assertThrows(IllegalArgumentException.class,
                () -> new MaterialTextureAnalysisSource(1, 1, -1, texture));
        assertThrows(NullPointerException.class,
                () -> new MaterialTextureAnalysisSource(1, 1, 1, null));
    }
}
