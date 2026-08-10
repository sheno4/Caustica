package dev.comfyfluffy.caustica.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.rt.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.rt.provider.MinecraftMaterialSource;
import dev.comfyfluffy.caustica.rt.provider.MinecraftSceneProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaRegistryTest {
    @Test
    void builtInFeatureSuppliesEveryDefaultSlot() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();

        CausticaRegistry.Selection selection = registry.selection();

        assertEquals(BuiltinExtension.ID, selection.binding(Slots.SKY).feature().id());
        // Surfaces are a set, not a slot: the built-in registers first so it is always index 0, the
        // fallback a material with no explicit choice resolves to.
        assertEquals(1, selection.surfaces().size());
        assertEquals("BuiltinSurface", selection.surfaces().get(0).type());
        assertEquals(0, registry.surfaceIndex(BuiltinExtension.BUILTIN_SURFACE));
        assertEquals(-1, registry.surfaceIndex(Identifier.fromNamespaceAndPath("nope", "nope")));
        assertTrue(registry.renderPasses().containsKey(
                Identifier.fromNamespaceAndPath("caustica", "bloom")));
        assertTrue(registry.renderPasses().containsKey(
                Identifier.fromNamespaceAndPath("caustica", "sky_lut")));
        assertTrue(registry.sceneProviders().containsKey(MinecraftSceneProvider.ID));
        assertTrue(registry.lightProviders().containsKey(MinecraftLightProvider.ID));
        assertTrue(registry.materialSources().containsKey(MinecraftMaterialSource.ID));
    }

    @Test
    void slotSelectionIsExplicitAndCanReturnToTheDefault() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Identifier featureId = Identifier.fromNamespaceAndPath("test", "sky");
        registry.feature(featureId)
                .title(Component.literal("Test sky"))
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
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Identifier featureId = registerTestSky(registry);

        assertEquals(List.of(BuiltinExtension.ID, featureId), registry.candidates(Slots.SKY));
    }

    @Test
    void defaultAndExplicitSelectionAreDistinguishable() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Identifier featureId = registerTestSky(registry);

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

    @Test
    void aPersistedSelectionAppliesOnlyWhenTheFeatureIsInstalledAndBindsTheSlot() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Identifier featureId = registerTestSky(registry);

        CausticaApi.applyPersistedSelection(registry, Slots.SKY, featureId.toString());
        assertEquals(featureId, registry.selectedFeature(Slots.SKY));

        // An uninstalled feature, a malformed id, and one that binds a different slot all fall back rather
        // than throwing: an extension the player removed must not stop the game from starting.
        registry.selectDefault(Slots.SKY);
        CausticaApi.applyPersistedSelection(registry, Slots.SKY, "test:not_installed");
        assertTrue(registry.isDefaultSelected(Slots.SKY));

        CausticaApi.applyPersistedSelection(registry, Slots.SKY, "NOT AN ID");
        assertTrue(registry.isDefaultSelected(Slots.SKY));

        CausticaApi.applyPersistedSelection(registry, Slots.SKY, null);
        assertTrue(registry.isDefaultSelected(Slots.SKY));
    }

    private static Identifier registerTestSky(CausticaRegistry registry) {
        Identifier featureId = Identifier.fromNamespaceAndPath("test", "sky");
        registry.feature(featureId)
                .title(Component.literal("Test sky"))
                .category(FeatureCategory.SKY)
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky")
                .register();
        return featureId;
    }

    @Test
    void rejectsDuplicateFeaturesBindingsAndOptions() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        assertThrows(IllegalStateException.class, () -> registry.feature(BuiltinExtension.ID).register());

        Identifier id = Identifier.fromNamespaceAndPath("test", "invalid");
        FeatureBuilder builder = registry.feature(id)
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky");
        assertThrows(IllegalStateException.class,
                () -> builder.bind(Slots.SKY, "second_sky", "SecondSky"));

        FeatureBuilder optionBuilder = registry.feature(Identifier.fromNamespaceAndPath("test", "options"))
                .option(Option.bool("enabled", true));
        assertThrows(IllegalStateException.class,
                () -> optionBuilder.option(Option.bool("enabled", false)));

        var bloom = registry.renderPasses().get(
                Identifier.fromNamespaceAndPath("caustica", "bloom"));
        assertThrows(IllegalStateException.class, () -> registry.feature(
                        Identifier.fromNamespaceAndPath("test", "duplicate_pass"))
                .renderPass(bloom).register());

        SceneProvider duplicateScene = () -> MinecraftSceneProvider.ID;
        assertThrows(IllegalStateException.class, () -> registry.feature(
                        Identifier.fromNamespaceAndPath("test", "duplicate_scene"))
                .sceneProvider(duplicateScene).register());
    }
}
