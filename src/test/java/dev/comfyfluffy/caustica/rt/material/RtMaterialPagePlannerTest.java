package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialPagePlannerTest {
    @Test
    void semanticBaseColorFlagsDoNotRequireMaterialImages() {
        int semanticOnly = RtMaterialRegistry.FEATURE_SUBSURFACE_COLOR_BASE
                | RtMaterialRegistry.FEATURE_EMISSION_COLOR_BASE;

        assertEquals(0, RtMaterialPageCompiler.pageChannels(semanticOnly));
        assertEquals(RtMaterialPagePlanner.CHANNEL_MATERIAL,
                RtMaterialPageCompiler.pageChannels(RtMaterialRegistry.FEATURE_SPEC));
    }

    @Test
    void plannerOrdersMaterialAndEmissionLayoutsDeterministically() {
        List<RtMaterialPagePlanner.Input> inputs = List.of(
                new RtMaterialPagePlanner.Input(0, "z-emission", 28, 28,
                        RtMaterialPagePlanner.CHANNEL_EMISSION),
                new RtMaterialPagePlanner.Input(1, "material", 28, 23,
                        RtMaterialPagePlanner.CHANNEL_MATERIAL));

        RtMaterialPagePlanner.Plan forward = RtMaterialPagePlanner.plan(inputs, 32, 32, 2, 1);
        RtMaterialPagePlanner.Plan reversed = RtMaterialPagePlanner.plan(inputs.reversed(), 32, 32, 2, 1);

        assertEquals(forward, reversed);
        assertEquals(2, forward.layouts().size());
        assertTrue(forward.layouts().get(0).has(RtMaterialPagePlanner.CHANNEL_MATERIAL));
        assertFalse(forward.layouts().get(1).has(RtMaterialPagePlanner.CHANNEL_MATERIAL));
        assertNotNull(forward.placement(0));
        assertNotNull(forward.placement(1));
    }

    @Test
    void metadataInputsNeedNoMaterialImages() {
        RtMaterialPagePlanner.Plan plan = RtMaterialPagePlanner.plan(List.of(
                new RtMaterialPagePlanner.Input(0, "metadata", 16, 16, 0)), 32, 32, 2, 1);

        assertNull(plan.placement(0));
        assertFalse(plan.layouts().get(0).has(RtMaterialPagePlanner.CHANNEL_MATERIAL));

        MaterialPagePacker pixels = new MaterialPagePacker(32, 6, 2, false, false);
        assertNull(pixels.surface0);
        assertNull(pixels.normal);
        assertNull(pixels.surface1);
    }

    @Test
    void oversizedInputsRemainUnpublishedForFallback() {
        RtMaterialPagePlanner.Plan plan = RtMaterialPagePlanner.plan(List.of(
                new RtMaterialPagePlanner.Input(0, "oversized", 33, 1,
                        RtMaterialPagePlanner.CHANNEL_MATERIAL)), 32, 32, 0, 1);

        assertTrue(plan.rejectedOversizedInput());
        assertNull(plan.placement(0));
        assertEquals(1, plan.layouts().size());
        assertEquals(0, plan.layouts().get(0).channels());
    }

    @Test
    void oversizedInputDoesNotInflatePageSizeForEligibleInputs() {
        RtMaterialPagePlanner.Plan plan = RtMaterialPagePlanner.plan(List.of(
                new RtMaterialPagePlanner.Input(0, "oversized", 65, 1,
                        RtMaterialPagePlanner.CHANNEL_MATERIAL),
                new RtMaterialPagePlanner.Input(1, "small", 8, 8,
                        RtMaterialPagePlanner.CHANNEL_EMISSION)), 16, 64, 2, 1);

        assertTrue(plan.rejectedOversizedInput());
        assertNull(plan.placement(0));
        assertNotNull(plan.placement(1));
        assertEquals(16, plan.pageSize());
    }
}
