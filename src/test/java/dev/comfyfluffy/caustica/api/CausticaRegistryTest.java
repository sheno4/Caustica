package dev.comfyfluffy.caustica.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CausticaRegistryTest {
    @Test
    void builtInFeatureSuppliesEveryDefaultSlot() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();

        CausticaRegistry.Selection selection = registry.selection();

        assertEquals(BuiltinExtension.ID, selection.binding(Slots.SKY).feature().id());
        assertEquals("BuiltinSurface", selection.binding(Slots.SURFACE).binding().type());
        assertEquals("BuiltinMedium", selection.binding(Slots.MEDIUM).binding().type());
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
    }
}
