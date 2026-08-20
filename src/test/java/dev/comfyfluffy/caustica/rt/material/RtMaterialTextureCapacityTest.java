package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialTextureCapacityTest {
    @Test
    void unifiedProviderTableBudgetsBaseTexturesAndFourResourcesPerPage() {
        assertEquals(256 + 4 * 256, RtMaterialEpoch.TEXTURE_CAPACITY);
    }
}
