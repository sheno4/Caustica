package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MaterialRuleTest {
    @Test
    void carriesOnlyStableNamesAndOpenPbrParameters() {
        MaterialRule rule = new MaterialRule(id("rule"),
                new MaterialRule.Match(ResourceId.parse("minecraft:block/amethyst_block"), null),
                new MaterialRule.Parameters(0.2f, 0.0f, 1.5f, 0.0f, 24.0f,
                        ResourceId.parse("test:crystal")));

        assertEquals(ResourceId.parse("test:crystal"), rule.parameters().surface());
        assertEquals(ResourceId.parse("minecraft:block/amethyst_block"), rule.match().material());
    }

    @Test
    void rejectsValuesOutsideTheSupportedSubset() {
        assertThrows(IllegalArgumentException.class, () ->
                new MaterialRule.Parameters(1.1f, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new MaterialRule.Parameters(null, null, 0.0f, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new MaterialRule.Parameters(null, null, null, null, Float.POSITIVE_INFINITY, null));
    }

    @Test
    void texturelessDefinitionsHaveStableNamesAndUniformBaseColor() {
        MaterialDefinition definition = new MaterialDefinition(new MaterialHandle(ResourceId.parse("caustica:cloud")),
                0.82f, 0.86f, 0.9f, 0.92f, 0.0f, 1.33f, 0.0f, MaterialTopology.SURFACE, null);

        assertEquals(ResourceId.parse("caustica:cloud"), definition.id());
        assertEquals(0.9f, definition.baseColorB());
        assertEquals(MaterialTopology.SURFACE, definition.topology());
    }

    @Test
    void transmissionDoesNotImplicitlyCreateAMediumBoundary() {
        MaterialDefinition thinSheet = new MaterialDefinition(new MaterialHandle(id("thin_sheet")),
                1.0f, 1.0f, 1.0f, 0.2f, 0.0f, 1.5f, 1.0f, MaterialTopology.SURFACE, null);

        assertEquals(1.0f, thinSheet.transmissionWeight());
        assertEquals(MaterialTopology.SURFACE, thinSheet.topology());
    }

    private static ResourceId id(String path) {
        return ResourceId.of("test", path);
    }
}
