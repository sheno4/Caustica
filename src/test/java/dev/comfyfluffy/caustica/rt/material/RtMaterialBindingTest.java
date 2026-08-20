package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.rt.gen.MaterialBindingData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binding word is read out of one aligned load by both any-hit entry points, so its field layout is a
 * cross-language ABI with {@code world_common.slang}'s binding accessors. Nothing renders wrong loudly if
 * a shift drifts — every surface just samples the wrong texture or tests the wrong threshold.
 */
final class RtMaterialBindingTest {
    @Test
    void bindingFieldsSurviveTheirNeighboursAtFullRange() {
        int packed = MaterialBindingAbi.pack(0xFFFF, 2, 63, 255);
        assertEquals(0xFFFF, MaterialBindingAbi.baseColorTextureIndex(packed));
        assertEquals(2, MaterialBindingAbi.coverage(packed));
        assertEquals(63, MaterialBindingAbi.flags(packed));
        assertEquals(255, MaterialBindingAbi.surfaceImplementation(packed));
        // Pinned literally: the shader decodes this word with its own shifts, so the exact bit positions
        // are the contract, not just the round trip through these helpers.
        assertEquals(0xFFFEFFFF, packed);
    }

    @Test
    void oneFieldNeverBleedsIntoAnother() {
        int slotOnly = MaterialBindingAbi.pack(0xFFFF, 0, 0, 0);
        assertEquals(0, MaterialBindingAbi.coverage(slotOnly));
        assertEquals(0, MaterialBindingAbi.flags(slotOnly));
        assertEquals(0, MaterialBindingAbi.surfaceImplementation(slotOnly));
        int flagsOnly = MaterialBindingAbi.pack(0, 0, 63, 0);
        assertEquals(0, MaterialBindingAbi.baseColorTextureIndex(flagsOnly));
        assertEquals(0, MaterialBindingAbi.coverage(flagsOnly));
        assertEquals(0, MaterialBindingAbi.surfaceImplementation(flagsOnly));
        int implOnly = MaterialBindingAbi.pack(0, 0, 0, 255);
        assertEquals(0, MaterialBindingAbi.baseColorTextureIndex(implOnly));
        assertEquals(0, MaterialBindingAbi.flags(implOnly));
    }

    @Test
    void texturelessDefinitionFlagOccupiesAStableBindingBit() {
        int packed = MaterialBindingAbi.pack(0, 0,
                MaterialBindingAbi.FLAG_TEXTURELESS, 0);
        assertEquals(4, MaterialBindingAbi.flags(packed));
        assertEquals(0x00100000, packed);
    }

    @Test
    void producerTextureUsesLinearSamplingWithoutChangingCoverage() {
        int flags = MaterialBindingAbi.FLAG_TEXTURELESS | 8;
        MaterialBindingData base = new MaterialBindingData(
                MaterialBindingAbi.pack(0, 2, flags, 7), 11, 0x123456, 19);

        MaterialBindingData textured = RtMaterialRegistry.baseColorTextureBinding(base, 23);

        assertEquals(23, MaterialBindingAbi.baseColorTextureIndex(textured.packed0()));
        assertEquals(2, MaterialBindingAbi.coverage(textured.packed0()));
        assertEquals(8 | MaterialBindingAbi.FLAG_BASE_COLOR_LINEAR,
                MaterialBindingAbi.flags(textured.packed0()));
        assertEquals(7, MaterialBindingAbi.surfaceImplementation(textured.packed0()));
        assertEquals(base.surface(), textured.surface());
        assertEquals(base.reserved(), textured.reserved());
        assertEquals(base.packed1(), textured.packed1());
    }

    @Test
    void coverageCutoffSurvivesQuantisation() {
        // The two cutoffs terrain and entity materials compile with. An 8-bit unorm must land close enough
        // that no texel changes side of the alpha test — sprite alpha is itself 8-bit.
        for (float cutoff : new float[]{0.5f, 0.1f}) {
            float decoded = MaterialBindingAbi.coverageCutoff(
                    MaterialBindingAbi.packCoverageCutoff(cutoff));
            assertTrue(Math.abs(decoded - cutoff) <= 1.0f / 255.0f,
                    "cutoff " + cutoff + " decoded as " + decoded);
        }
    }

}
