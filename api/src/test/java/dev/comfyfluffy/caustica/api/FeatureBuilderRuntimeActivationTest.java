package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.option.Option;
import dev.comfyfluffy.caustica.api.shader.ShaderSource;
import dev.comfyfluffy.caustica.api.scene.SceneProvider;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FeatureBuilderRuntimeActivationTest {
    private static final ShaderSource SHADERS = ShaderSource.classpath(Object.class, "/test/shaders");
    /** No engine slot exists, so SELECTED_SLOT is exercised against one a test declares for itself. */
    private static final Slot SLOT = new Slot(ResourceId.of("test", "slot"), "test_slot", "TestSlot");

    @Test
    void rejectsUnboundSelectedSlotRuntimeContributions() {
        assertUnboundSelectedSlotRejected(builder -> builder.worldResourcePass(
                id("world-resource"), context -> null));
        assertUnboundSelectedSlotRejected(builder -> builder.postEffectPass(
                id("post-effect"), context -> null));
        assertUnboundSelectedSlotRejected(builder -> builder.uiPass(
                id("ui"), context -> null));
        assertUnboundSelectedSlotRejected(builder -> builder.sceneProvider(
                id("scene"), context -> new SceneProvider() { }));
    }

    @Test
    void allowsUnboundSelectedSlotCompositionOnlyFeature() {
        CausticaRegistry registry = new CausticaRegistry();

        assertDoesNotThrow(() -> registry.feature(id("composition"))
                .runtimeActivation(RuntimeActivation.SELECTED_SLOT)
                .shaderSource(SHADERS)
                .surface(id("surface"), "test_surface", "TestSurface",
                        id("coverage"), "test_coverage", "TestCoverage")
                .surfaceModifier(id("modifier"), "test_modifier", "TestModifier")
                .option(Option.bool("enabled", true))
                .register());
    }

    @Test
    void allowsUnboundAlwaysRuntimeContribution() {
        CausticaRegistry registry = new CausticaRegistry();

        assertDoesNotThrow(() -> registry.feature(id("always"))
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .sceneProvider(id("scene"), context -> new SceneProvider() { })
                .register());
    }

    @Test
    void allowsSelectedSlotRuntimeContributionWithBinding() {
        CausticaRegistry registry = new CausticaRegistry();

        assertDoesNotThrow(() -> registry.feature(id("selected"))
                .runtimeActivation(RuntimeActivation.SELECTED_SLOT)
                .shaderSource(SHADERS)
                .bind(SLOT, "test_sky", "TestSky")
                .sceneProvider(id("scene"), context -> new SceneProvider() { })
                .register());
    }

    private static void assertUnboundSelectedSlotRejected(Consumer<FeatureBuilder> contribution) {
        CausticaRegistry registry = new CausticaRegistry();
        FeatureBuilder builder = registry.feature(id("unbound"))
                .runtimeActivation(RuntimeActivation.SELECTED_SLOT);
        contribution.accept(builder);

        IllegalStateException exception = assertThrows(IllegalStateException.class, builder::register);
        assertTrue(exception.getMessage().contains("binds no slot"));
    }

    private static ResourceId id(String path) {
        return ResourceId.of("test", path);
    }
}
