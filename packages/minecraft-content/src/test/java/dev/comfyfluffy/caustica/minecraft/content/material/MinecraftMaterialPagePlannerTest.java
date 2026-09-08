package dev.comfyfluffy.caustica.minecraft.content.material;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMaterialPagePlannerTest {
    @Test
    void fullPageMaterialDoesNotReserveSpaceForSeparateNeutralTextures() {
        var plan = MinecraftMaterialPagePlanner.plan(List.of(
                new MinecraftMaterialPagePlanner.Input(0, "full", 28, 28,
                        MinecraftMaterialPagePlanner.CHANNEL_MATERIAL)), 32, 32, 2, 1);

        assertEquals(1, plan.layouts().size());
        assertEquals(List.of(new MinecraftMaterialPagePlanner.Placement(0, 0, 2, 2)), plan.placements());
    }

    @Test
    void rejectedPlacementLeavesTheCurrentRowAvailableToSmallerInputs() {
        var plan = MinecraftMaterialPagePlanner.plan(List.of(
                new MinecraftMaterialPagePlanner.Input(0, "first", 10, 10,
                        MinecraftMaterialPagePlanner.CHANNEL_MATERIAL),
                new MinecraftMaterialPagePlanner.Input(1, "next-page", 8, 8,
                        MinecraftMaterialPagePlanner.CHANNEL_MATERIAL),
                new MinecraftMaterialPagePlanner.Input(2, "remaining-row", 6, 7,
                        MinecraftMaterialPagePlanner.CHANNEL_MATERIAL)), 16, 16, 0, 1);

        assertEquals(List.of(new MinecraftMaterialPagePlanner.Placement(0, 0, 0, 0),
                new MinecraftMaterialPagePlanner.Placement(1, 1, 0, 0),
                new MinecraftMaterialPagePlanner.Placement(2, 0, 10, 0)), plan.placements());
    }

    @Test
    void semanticBaseColorFlagsDoNotRequireMaterialImages() {
        int semanticOnly = MinecraftMaterialPageCompiler.FEATURE_SUBSURFACE_COLOR_BASE
                | MinecraftMaterialPageCompiler.FEATURE_EMISSION_COLOR_BASE;

        assertEquals(0, MinecraftMaterialPageCompiler.pageChannels(semanticOnly));
        assertEquals(MinecraftMaterialPagePlanner.CHANNEL_MATERIAL,
                MinecraftMaterialPageCompiler.pageChannels(MinecraftMaterialPageCompiler.FEATURE_SPEC));
    }

    @Test
    void plannerOrdersMaterialAndEmissionLayoutsDeterministically() {
        List<MinecraftMaterialPagePlanner.Input> inputs = List.of(
                new MinecraftMaterialPagePlanner.Input(0, "z-emission", 28, 28,
                        MinecraftMaterialPagePlanner.CHANNEL_EMISSION),
                new MinecraftMaterialPagePlanner.Input(1, "material", 28, 23,
                        MinecraftMaterialPagePlanner.CHANNEL_MATERIAL));

        MinecraftMaterialPagePlanner.Plan forward = MinecraftMaterialPagePlanner.plan(inputs, 32, 32, 2, 1);
        MinecraftMaterialPagePlanner.Plan reversed = MinecraftMaterialPagePlanner.plan(inputs.reversed(), 32, 32, 2, 1);

        assertEquals(forward, reversed);
        assertEquals(2, forward.layouts().size());
        assertTrue(forward.layouts().get(0).has(MinecraftMaterialPagePlanner.CHANNEL_MATERIAL));
        assertFalse(forward.layouts().get(1).has(MinecraftMaterialPagePlanner.CHANNEL_MATERIAL));
        assertNotNull(forward.placement(0));
        assertNotNull(forward.placement(1));
    }

    @Test
    void metadataInputsNeedNoMaterialImages() {
        MinecraftMaterialPagePlanner.Plan plan = MinecraftMaterialPagePlanner.plan(List.of(
                new MinecraftMaterialPagePlanner.Input(0, "metadata", 16, 16, 0)), 32, 32, 2, 1);

        assertNull(plan.placement(0));
        assertTrue(plan.layouts().isEmpty());

        MaterialPagePacker pixels = new MaterialPagePacker(32, 6, 2, false, false);
        assertNull(pixels.surface0);
        assertNull(pixels.normal);
        assertNull(pixels.surface1);
    }

    @Test
    void oversizedInputsRemainUnpublishedForFallback() {
        MinecraftMaterialPagePlanner.Plan plan = MinecraftMaterialPagePlanner.plan(List.of(
                new MinecraftMaterialPagePlanner.Input(0, "oversized", 33, 1,
                        MinecraftMaterialPagePlanner.CHANNEL_MATERIAL)), 32, 32, 0, 1);

        assertTrue(plan.rejectedOversizedInput());
        assertNull(plan.placement(0));
        assertTrue(plan.layouts().isEmpty());
    }

    @Test
    void oversizedInputDoesNotInflatePageSizeForEligibleInputs() {
        MinecraftMaterialPagePlanner.Plan plan = MinecraftMaterialPagePlanner.plan(List.of(
                new MinecraftMaterialPagePlanner.Input(0, "oversized", 65, 1,
                        MinecraftMaterialPagePlanner.CHANNEL_MATERIAL),
                new MinecraftMaterialPagePlanner.Input(1, "small", 8, 8,
                        MinecraftMaterialPagePlanner.CHANNEL_EMISSION)), 16, 64, 2, 1);

        assertTrue(plan.rejectedOversizedInput());
        assertNull(plan.placement(0));
        assertNotNull(plan.placement(1));
        assertEquals(16, plan.pageSize());
    }
}
