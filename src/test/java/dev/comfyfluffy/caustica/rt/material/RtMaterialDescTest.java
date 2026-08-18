package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtMaterialDescTest {
    @Test
    void retainsCompilerSourceAndNormalizedEmissionMetadata() {
        RtMaterialDesc.EmissionSummary summary = new RtMaterialDesc.EmissionSummary(
                0.4f, 0.2f, 0.1f, 0.25f, 0.5f);
        RtMaterialDesc desc = new RtMaterialDesc(0, RtMaterialDesc.Source.DERIVED_TEXTURE, 0,
                0.7f, 0.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.DERIVED_MASK,
                1.0f, summary, 0);
        assertEquals(RtMaterialDesc.Source.DERIVED_TEXTURE, desc.source());
        assertEquals(RtMaterialDesc.EmissionSource.DERIVED_MASK, desc.emissionSource());
        assertEquals(summary, desc.emissionSummary());
    }

    /**
     * The compiled binding carries the implementation index in eight bits, so an out-of-range one would
     * alias another registered implementation rather than fail anywhere visible.
     */
    @Test
    void rejectsASurfaceImplementationTheBindingCannotCarry() {
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                RtMaterialDesc.Source.DERIVED_TEXTURE, 0, 0.5f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE, 256));
    }

    @Test
    void rejectsInvalidPhysicalParameters() {
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                RtMaterialDesc.Source.DERIVED_TEXTURE, 0, 1.1f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                RtMaterialDesc.Source.DERIVED_TEXTURE, 0, 0.5f, 0.0f, 0.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE, 0));
    }

    @Test
    void namedEmissionUsesUniformBaselineBeforeItsTextureMask() {
        int features = RtMaterialRegistry.definitionFeatures(true,
                RtMaterialRegistry.FEATURE_EMISSION_MASK, 12.0f);
        assertEquals(RtMaterialRegistry.FEATURE_EMISSION_MASK
                | RtMaterialRegistry.FEATURE_UNIFORM_EMISSION, features);
    }
}
