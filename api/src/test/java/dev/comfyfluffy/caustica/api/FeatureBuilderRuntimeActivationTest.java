package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FeatureBuilderRuntimeActivationTest {
    private static final ShaderSource SHADERS = ShaderSource.classpath("/test/shaders");

    @Test
    void rejectsUnboundSelectedSlotRuntimeContributions() {
        assertUnboundSelectedSlotRejected(builder -> builder.renderPass(
                id("pass"), RenderStage.BEFORE_TRACE, () -> null));
        assertUnboundSelectedSlotRejected(builder -> builder.sceneProvider(
                id("scene"), () -> new SceneProvider() { }));
        assertUnboundSelectedSlotRejected(builder -> builder.lightProvider(
                id("light"), () -> new LightProvider() { }));
        assertUnboundSelectedSlotRejected(builder -> builder.materialSource(
                id("materials"), () -> sink -> { }));
    }

    @Test
    void allowsUnboundSelectedSlotCompositionOnlyFeature() {
        CausticaRegistry registry = new CausticaRegistry();

        assertDoesNotThrow(() -> registry.feature(id("composition"))
                .shaderSource(SHADERS)
                .surface(id("surface"), "test_surface", "TestSurface")
                .surfaceModifier(id("modifier"), "test_modifier", "TestModifier")
                .option(Option.bool("enabled", true))
                .register());
    }

    @Test
    void allowsUnboundAlwaysRuntimeContribution() {
        CausticaRegistry registry = new CausticaRegistry();

        assertDoesNotThrow(() -> registry.feature(id("always"))
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .sceneProvider(id("scene"), () -> new SceneProvider() { })
                .register());
    }

    @Test
    void allowsSelectedSlotRuntimeContributionWithBinding() {
        CausticaRegistry registry = new CausticaRegistry();

        assertDoesNotThrow(() -> registry.feature(id("selected"))
                .shaderSource(SHADERS)
                .bind(Slots.SKY, "test_sky", "TestSky")
                .sceneProvider(id("scene"), () -> new SceneProvider() { })
                .register());
    }

    private static void assertUnboundSelectedSlotRejected(Consumer<FeatureBuilder> contribution) {
        CausticaRegistry registry = new CausticaRegistry();
        FeatureBuilder builder = registry.feature(id("unbound"));
        contribution.accept(builder);

        IllegalStateException exception = assertThrows(IllegalStateException.class, builder::register);
        assertTrue(exception.getMessage().contains("binds no slot"));
    }

    private static ResourceId id(String path) {
        return ResourceId.of("test", path);
    }
}
