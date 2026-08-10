package dev.comfyfluffy.caustica.rt.material;

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
        int packed = RtMaterialRegistry.packBinding0(0xFFFF, 2, 63, 255);
        assertEquals(0xFFFF, RtMaterialRegistry.bindingAlbedoSlot(packed));
        assertEquals(2, RtMaterialRegistry.bindingCoverage(packed));
        assertEquals(63, RtMaterialRegistry.bindingFlags(packed));
        assertEquals(255, RtMaterialRegistry.bindingSurfaceImpl(packed));
        // Pinned literally: the shader decodes this word with its own shifts, so the exact bit positions
        // are the contract, not just the round trip through these helpers.
        assertEquals(0xFFFEFFFF, packed);
    }

    @Test
    void oneFieldNeverBleedsIntoAnother() {
        int slotOnly = RtMaterialRegistry.packBinding0(0xFFFF, 0, 0, 0);
        assertEquals(0, RtMaterialRegistry.bindingCoverage(slotOnly));
        assertEquals(0, RtMaterialRegistry.bindingFlags(slotOnly));
        assertEquals(0, RtMaterialRegistry.bindingSurfaceImpl(slotOnly));
        int flagsOnly = RtMaterialRegistry.packBinding0(0, 0, 63, 0);
        assertEquals(0, RtMaterialRegistry.bindingAlbedoSlot(flagsOnly));
        assertEquals(0, RtMaterialRegistry.bindingCoverage(flagsOnly));
        assertEquals(0, RtMaterialRegistry.bindingSurfaceImpl(flagsOnly));
        int implOnly = RtMaterialRegistry.packBinding0(0, 0, 0, 255);
        assertEquals(0, RtMaterialRegistry.bindingAlbedoSlot(implOnly));
        assertEquals(0, RtMaterialRegistry.bindingFlags(implOnly));
    }

    @Test
    void texturelessDefinitionFlagOccupiesAStableBindingBit() {
        int packed = RtMaterialRegistry.packBinding0(0, 0,
                RtMaterialRegistry.BINDING_TEXTURELESS, 0);
        assertEquals(4, RtMaterialRegistry.bindingFlags(packed));
        assertEquals(0x00100000, packed);
    }

    @Test
    void coverageCutoffSurvivesQuantisation() {
        // The two cutoffs terrain and entity materials compile with. An 8-bit unorm must land close enough
        // that no texel changes side of the alpha test — sprite alpha is itself 8-bit.
        for (float cutoff : new float[]{0.5f, 0.1f}) {
            float decoded = RtMaterialRegistry.unpackCoverageCutoff(
                    RtMaterialRegistry.packCoverageCutoff(cutoff));
            assertTrue(Math.abs(decoded - cutoff) <= 1.0f / 255.0f,
                    "cutoff " + cutoff + " decoded as " + decoded);
        }
    }

    @Test
    void clearGlassStillDimsShadowRays() {
        // A white sprite with no alpha coverage carries no colour extinction, so only the neutral term
        // applies: exp(-0.15). This is the case a purely colour-derived tint would leave fully invisible
        // to a shadow ray.
        int tint = RtMaterialRegistry.translucentShadowTint(new float[]{1.0f, 1.0f, 1.0f, 0.0f});
        int expected = Math.round((float) Math.exp(-0.15) * 255.0f);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expected, (tint >>> (channel * 8)) & 0xFF);
        }
        assertEquals(0, tint >>> 24, "shadow tint occupies the low 24 bits only");
    }

    @Test
    void saturatedPanesAbsorbTheirOwnColour() {
        // A blue pane covering its whole footprint: red absorbs, blue passes. ACEScg mixes the primaries,
        // so the assertion is the ordering the shadow path depends on, not an exact triple.
        int tint = RtMaterialRegistry.translucentShadowTint(new float[]{0.1f, 0.1f, 0.9f, 1.0f});
        int red = tint & 0xFF;
        int blue = (tint >>> 16) & 0xFF;
        assertTrue(blue > red, "blue transmittance " + blue + " should exceed red " + red);
        assertTrue(red < Math.round((float) Math.exp(-0.15) * 255.0f),
                "an absorbing pane must dim more than clear glass");
    }
}
