package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftApiBootstrapTest {
    @Test
    void persistedSelectionAppliesOnlyToAnInstalledFeatureBindingTheSlot() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        ResourceId featureId = ResourceId.of("test", "sky");
        registry.feature(featureId)
                .title(DisplayText.literal("Test sky"))
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky")
                .register();

        MinecraftApiBootstrap.applyPersistedSelection(registry, Slots.SKY, featureId.toString());
        assertEquals(featureId, registry.selectedFeature(Slots.SKY));

        registry.selectDefault(Slots.SKY);
        MinecraftApiBootstrap.applyPersistedSelection(registry, Slots.SKY, "test:not_installed");
        MinecraftApiBootstrap.applyPersistedSelection(registry, Slots.SKY, "NOT AN ID");
        MinecraftApiBootstrap.applyPersistedSelection(registry, Slots.SKY, null);
        assertTrue(registry.isDefaultSelected(Slots.SKY));
    }
}
