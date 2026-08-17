package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertTrue(registry.renderPassIds().contains(ResourceId.of("caustica", "bloom")));
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

        ResourceId bloom = ResourceId.of("caustica", "bloom");
        assertThrows(IllegalStateException.class, () -> registry.feature(
                        ResourceId.of("test", "duplicate_pass"))
                .renderPass(bloom, RenderStage.AFTER_RECONSTRUCTION,
                        () -> pass(bloom, RenderStage.AFTER_RECONSTRUCTION)).register());

        SceneProvider duplicateScene = new SceneProvider() {
        };
        ResourceId sceneId = ResourceId.of("test", "scene");
        registry.feature(ResourceId.of("test", "scene_owner"))
                .sceneProvider(sceneId, () -> duplicateScene).register();
        assertThrows(IllegalStateException.class, () -> registry.feature(
                        ResourceId.of("test", "duplicate_scene"))
                .sceneProvider(sceneId, () -> duplicateScene).register());
    }

    @Test
    void surfaceModifiersPreserveRegistrationOrderAndRejectDuplicateIds() {
        CausticaRegistry registry = builtins();
        ResourceId first = ResourceId.of("test", "first_modifier");
        ResourceId second = ResourceId.of("test", "second_modifier");
        registry.feature(ResourceId.of("test", "modifier_owner"))
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .surfaceModifier(first, "first_modifier", "FirstModifier")
                .surfaceModifier(second, "second_modifier", "SecondModifier")
                .register();

        assertEquals(List.of(first, second), registry.selection().surfaceModifiers().stream()
                .map(Feature.SurfaceModifierImplementation::id).toList());
        assertThrows(IllegalStateException.class, () -> registry.feature(
                        ResourceId.of("test", "duplicate_modifier_owner"))
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .surfaceModifier(first, "duplicate_modifier", "DuplicateModifier")
                .register());
    }

    @Test
    void runtimeFactoriesFollowRuntimeActivationRatherThanProgramClosure() {
        CausticaRegistry registry = builtins();
        AtomicInteger selectedFactories = new AtomicInteger();
        AtomicInteger alwaysFactories = new AtomicInteger();
        ResourceId selectedId = ResourceId.of("test", "optional_sky");
        ResourceId selectedPass = ResourceId.of("test", "optional_pass");
        ResourceId alwaysProvider = ResourceId.of("test", "always_scene");
        registry.feature(selectedId)
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky")
                .renderPass(selectedPass, RenderStage.AFTER_RECONSTRUCTION, () -> {
                    selectedFactories.incrementAndGet();
                    return pass(selectedPass, RenderStage.AFTER_RECONSTRUCTION);
                })
                .register();
        registry.feature(ResourceId.of("test", "always"))
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .sceneProvider(alwaysProvider, () -> {
                    alwaysFactories.incrementAndGet();
                    return new SceneProvider() {
                    };
                })
                .register();

        CausticaRegistry.RuntimeContributions defaultRuntime = registry.createRuntimeContributions();
        assertFalse(defaultRuntime.renderPasses().containsKey(selectedPass));
        assertTrue(defaultRuntime.sceneProviders().containsKey(alwaysProvider));
        assertEquals(0, selectedFactories.get());
        assertEquals(1, alwaysFactories.get());
        assertEquals(BuiltinExtension.ID, defaultRuntime.selectedSlots().get(Slots.SKY));

        registry.select(Slots.SKY, selectedId);
        CausticaRegistry.RuntimeContributions selectedRuntime = registry.createRuntimeContributions();
        assertTrue(selectedRuntime.renderPasses().containsKey(selectedPass));
        assertEquals(selectedId, selectedRuntime.selectedSlots().get(Slots.SKY));
        assertEquals(1, selectedFactories.get());
        assertEquals(2, alwaysFactories.get());
    }

    @Test
    void runtimeFactoriesMustProduceTheirDeclaredPassIdentityAndStage() {
        CausticaRegistry nullPassRegistry = builtins();
        ResourceId nullPass = ResourceId.of("test", "null_pass");
        nullPassRegistry.feature(ResourceId.of("test", "null_pass_owner"))
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .renderPass(nullPass, RenderStage.AFTER_RECONSTRUCTION, () -> null)
                .register();
        assertThrows(NullPointerException.class, nullPassRegistry::createRuntimeContributions);

        CausticaRegistry wrongStageRegistry = builtins();
        ResourceId wrongStagePass = ResourceId.of("test", "wrong_stage_pass");
        wrongStageRegistry.feature(ResourceId.of("test", "wrong_stage_owner"))
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .renderPass(wrongStagePass, RenderStage.AFTER_RECONSTRUCTION,
                        () -> pass(wrongStagePass, RenderStage.OVERLAY))
                .register();
        assertThrows(IllegalStateException.class, wrongStageRegistry::createRuntimeContributions);
    }

    private static CausticaRegistry builtins() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        return registry;
    }

    private static CausticaRenderPass pass(ResourceId id, RenderStage stage) {
        return new CausticaRenderPass() {
            @Override
            public ResourceId id() {
                return id;
            }

            @Override
            public RenderStage stage() {
                return stage;
            }

            @Override
            public void record(PassFrame frame) {
            }
        };
    }
}
