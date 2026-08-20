package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the host-side layout read by the shader's material-binding accessors. */
final class RtMaterialBindingTest {
    @Test
    void bindingFieldsOccupyStableIndependentRanges() {
        int packed = MaterialBindingAbi.pack(0xFF, 0xA5);

        assertEquals(0xFF, MaterialBindingAbi.flags(packed));
        assertEquals(0xA5, MaterialBindingAbi.surfaceImplementation(packed));
        assertEquals(0xA50000FF, packed);
        assertEquals(0, MaterialBindingAbi.pack(0, 0));
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
