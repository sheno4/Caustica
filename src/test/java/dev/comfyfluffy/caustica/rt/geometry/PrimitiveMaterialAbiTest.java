package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PrimitiveMaterialAbiTest {
    @Test
    void constantsAndFieldsMirrorTheShaderAbi() {
        assertEquals(0, PrimitiveMaterialAbi.COVERAGE_OPAQUE);
        assertEquals(1, PrimitiveMaterialAbi.COVERAGE_CUTOUT);
        assertEquals(2, PrimitiveMaterialAbi.COVERAGE_STOCHASTIC);

        int packed = PrimitiveMaterialAbi.pack(0xFFFF, SceneMesh.Coverage.STOCHASTIC, true);
        assertEquals(0xFFFF, PrimitiveMaterialAbi.textureSlot(packed));
        assertEquals(PrimitiveMaterialAbi.COVERAGE_STOCHASTIC, PrimitiveMaterialAbi.coverage(packed));
        assertTrue(PrimitiveMaterialAbi.texturePresent(packed));
        assertTrue(PrimitiveMaterialAbi.textureLinear(packed));
        assertEquals(0x000EFFFF, packed);
    }

    @Test
    void absentTextureDoesNotSetSamplingFacts() {
        int packed = PrimitiveMaterialAbi.pack(0, SceneMesh.Coverage.OPAQUE, false);
        assertFalse(PrimitiveMaterialAbi.texturePresent(packed));
        assertFalse(PrimitiveMaterialAbi.textureLinear(packed));
    }

    @Test
    void rejectsUnrepresentableFields() {
        assertThrows(IllegalArgumentException.class,
                () -> PrimitiveMaterialAbi.pack(0x10000, SceneMesh.Coverage.OPAQUE, true));
        assertThrows(IllegalArgumentException.class,
                () -> PrimitiveMaterialAbi.coverage(3 << 16));
    }
}
