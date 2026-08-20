package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MaterialProviderDataTest {
    @Test
    void ownsExactlyTwelveOpaqueWords() {
        int[] source = new int[MaterialProviderData.WORD_COUNT];
        source[0] = 0x89ABCDEF;
        source[11] = -1;
        MaterialProviderData data = new MaterialProviderData(source);

        source[0] = 0;
        int[] copy = data.words();
        copy[11] = 0;

        assertEquals(0x89ABCDEF, data.word(0));
        assertEquals(-1, data.word(11));
        assertEquals(data, new MaterialProviderData(data.words()));
        assertNotEquals(data, MaterialProviderData.ZERO);
        assertArrayEquals(new int[MaterialProviderData.WORD_COUNT], MaterialProviderData.ZERO.words());
    }

    @Test
    void rejectsAnyOtherWordCount() {
        assertThrows(IllegalArgumentException.class, () -> new MaterialProviderData(new int[11]));
        assertThrows(IllegalArgumentException.class, () -> new MaterialProviderData(new int[13]));
    }

    @Test
    void materialConvenienceConstructorDefaultsToZeroData() {
        MaterialDefinition definition = new MaterialDefinition(
                new MaterialHandle(ResourceId.of("test", "plain")),
                1, 1, 1, 0.5f, 0, 1.5f, 0, MaterialTopology.SURFACE, null);

        assertEquals(MaterialProviderData.ZERO, definition.providerData());
    }
}
