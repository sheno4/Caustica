package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MaterialRuleTest {
    @Test
    void carriesOnlyStableNamesAndOpenPbrParameters() {
        MaterialRule rule = new MaterialRule(id("rule"),
                new MaterialRule.Match(ResourceId.parse("minecraft:entity/end_portal/end_portal"), null),
                new MaterialRule.Parameters(0.2f, 0.0f, 1.5f, 0.0f, 24.0f,
                        ResourceId.parse("caustica:end_portal")));

        assertEquals(ResourceId.parse("caustica:end_portal"), rule.parameters().surface());
        assertEquals(ResourceId.parse("minecraft:entity/end_portal/end_portal"), rule.match().material());
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
                0.82f, 0.86f, 0.9f, 0.92f, 0.0f, 1.33f, 0.0f, null);

        assertEquals(ResourceId.parse("caustica:cloud"), definition.id());
        assertEquals(0.9f, definition.baseColorB());
    }

    private static ResourceId id(String path) {
        return ResourceId.of("test", path);
    }
}
