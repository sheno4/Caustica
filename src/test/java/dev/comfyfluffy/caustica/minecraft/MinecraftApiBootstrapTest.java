package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApiExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtensionRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        MinecraftApiBootstrap.applyPersistedSelection(
                registry, Slots.SKY, featureId.toString(), BuiltinExtension.ID);
        assertEquals(featureId, registry.selectedFeature(Slots.SKY));

        registry.selectDefault(Slots.SKY);
        MinecraftApiBootstrap.applyPersistedSelection(
                registry, Slots.SKY, "test:not_installed", featureId);
        MinecraftApiBootstrap.applyPersistedSelection(
                registry, Slots.SKY, "NOT AN ID", featureId);
        assertTrue(registry.isDefaultSelected(Slots.SKY));
    }

    @Test
    void minecraftSkyIsTheHostDefaultButAnExplicitBuiltinSelectionIsPreserved() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        new MinecraftProvidersExtension().register(registry);

        MinecraftApiBootstrap.applyPersistedSelection(
                registry, Slots.SKY, null, MinecraftProvidersExtension.ID);
        assertEquals(MinecraftProvidersExtension.ID, registry.selectedFeature(Slots.SKY));

        MinecraftApiBootstrap.applyPersistedSelection(
                registry, Slots.SKY, BuiltinExtension.ID.toString(), MinecraftProvidersExtension.ID);
        assertEquals(BuiltinExtension.ID, registry.selectedFeature(Slots.SKY));
    }

    @Test
    void featureAndMinecraftEntrypointsFailIndependentlyBeforeRegistryFreeze() {
        CausticaRegistry features = new CausticaRegistry();
        MinecraftExtensionRegistry minecraft = new MinecraftExtensionRegistry();
        ResourceId ordinaryFeature = ResourceId.of("test", "ordinary_survives");
        MinecraftApiExtension ordinaryFails = new MinecraftApiExtension() {
            @Override public void register(CausticaRegistry registry) {
                throw new IllegalStateException("ordinary failure");
            }
            @Override public void registerMinecraft(MinecraftExtensionRegistry registry) {
                registry.registerMaterialResolver(ResourceId.of("test", "host_survives"), 0,
                        java.util.List.of(new dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialSelector(
                                null, null)), request -> null);
            }
        };
        MinecraftApiExtension hostFails = new MinecraftApiExtension() {
            @Override public void register(CausticaRegistry registry) {
                registry.feature(ordinaryFeature).register();
            }
            @Override public void registerMinecraft(MinecraftExtensionRegistry registry) {
                throw new IllegalStateException("host failure");
            }
        };

        MinecraftApiBootstrap.registerExtensions(features, minecraft, java.util.List.of(ordinaryFails, hostFails));

        assertTrue(features.features().containsKey(ordinaryFeature));
        assertTrue(minecraft.frozen());
        assertThrows(IllegalStateException.class, () -> minecraft.registerMaterialResolver(
                ResourceId.of("test", "late"), 0,
                java.util.List.of(new dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialSelector(null, null)),
                request -> null));
    }
}
