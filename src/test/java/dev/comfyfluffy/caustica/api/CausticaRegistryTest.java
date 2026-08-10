package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaRegistryTest {
    @Test
    void builtInFeatureSuppliesEveryDefaultSlot() {
        CausticaRegistry registry = builtins();

        CausticaRegistry.Selection selection = registry.selection();

        assertEquals(BuiltinExtension.ID, selection.binding(Slots.SKY).feature().id());
        // Surfaces are a set, not a slot: the built-in registers first so it is always index 0, the
        // fallback a material with no explicit choice resolves to.
        assertEquals(1, selection.surfaces().size());
        assertEquals("BuiltinSurface", selection.surfaces().get(0).type());
        assertEquals(0, registry.surfaceIndex(BuiltinExtension.BUILTIN_SURFACE));
        assertEquals(-1, registry.surfaceIndex(ResourceId.of("nope", "nope")));
        assertTrue(registry.renderPasses().containsKey(
                ResourceId.of("caustica", "bloom")));
    }

    @Test
    void slotSelectionIsExplicitAndCanReturnToTheDefault() {
        CausticaRegistry registry = builtins();
        ResourceId featureId = ResourceId.of("test", "sky");
        registry.feature(featureId)
                .title(DisplayText.literal("Test sky"))
                .category(FeatureCategory.SKY)
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky")
                .register();

        registry.select(Slots.SKY, featureId);
        assertEquals(featureId, registry.selection().binding(Slots.SKY).feature().id());

        registry.selectDefault(Slots.SKY);
        assertEquals(BuiltinExtension.ID, registry.selection().binding(Slots.SKY).feature().id());
    }

    @Test
    void candidatesListTheDefaultFirstSoARadioGroupCanMarkIt() {
        CausticaRegistry registry = builtins();
        ResourceId featureId = registerTestSky(registry);

        assertEquals(List.of(BuiltinExtension.ID, featureId), registry.candidates(Slots.SKY));
    }

    @Test
    void defaultAndExplicitSelectionAreDistinguishable() {
        CausticaRegistry registry = builtins();
        ResourceId featureId = registerTestSky(registry);

        assertTrue(registry.isDefaultSelected(Slots.SKY));
        assertEquals(BuiltinExtension.ID, registry.selectedFeature(Slots.SKY));

        registry.select(Slots.SKY, featureId);
        assertFalse(registry.isDefaultSelected(Slots.SKY));
        assertEquals(featureId, registry.selectedFeature(Slots.SKY));
        assertEquals(BuiltinExtension.ID, registry.defaultFeature(Slots.SKY),
                "the default is what the slot falls back to, not what is chosen");

        // Selecting the default explicitly still counts as a choice; only selectDefault clears it.
        registry.selectDefault(Slots.SKY);
        assertTrue(registry.isDefaultSelected(Slots.SKY));
    }

    private static ResourceId registerTestSky(CausticaRegistry registry) {
        ResourceId featureId = ResourceId.of("test", "sky");
        registry.feature(featureId)
                .title(DisplayText.literal("Test sky"))
                .category(FeatureCategory.SKY)
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky")
                .register();
        return featureId;
    }

    @Test
    void rejectsDuplicateFeaturesBindingsAndOptions() {
        CausticaRegistry registry = builtins();
        assertThrows(IllegalStateException.class, () -> registry.feature(BuiltinExtension.ID).register());

        ResourceId id = ResourceId.of("test", "invalid");
        FeatureBuilder builder = registry.feature(id)
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky");
        assertThrows(IllegalStateException.class,
                () -> builder.bind(Slots.SKY, "second_sky", "SecondSky"));

        FeatureBuilder optionBuilder = registry.feature(ResourceId.of("test", "options"))
                .option(Option.bool("enabled", true));
        assertThrows(IllegalStateException.class,
                () -> optionBuilder.option(Option.bool("enabled", false)));

        var bloom = registry.renderPasses().get(
                ResourceId.of("caustica", "bloom"));
        assertThrows(IllegalStateException.class, () -> registry.feature(
                        ResourceId.of("test", "duplicate_pass"))
                .renderPass(bloom).register());

        SceneProvider duplicateScene = new SceneProvider() {
        };
        ResourceId sceneId = ResourceId.of("test", "scene");
        registry.feature(ResourceId.of("test", "scene_owner"))
                .sceneProvider(sceneId, duplicateScene).register();
        assertThrows(IllegalStateException.class, () -> registry.feature(
                        ResourceId.of("test", "duplicate_scene"))
                .sceneProvider(sceneId, duplicateScene).register());
    }

    private static CausticaRegistry builtins() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        return registry;
    }
}
