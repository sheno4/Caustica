package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FeatureRuntimeContextTest {
    private static final ResourceId FEATURE = ResourceId.of("test", "context");
    private static final ResourceId PASS = ResourceId.of("test", "pass");
    private static final FeatureRuntimeContext.Key<Object> SHARED = new FeatureRuntimeContext.Key<>(
            ResourceId.of("test", "shared"), Object.class);

    @Test
    void oneContextAndValueAreSharedByEveryFactoryForAFeatureAndRenewedPerActivation() {
        CausticaRegistry registry = new CausticaRegistry();
        List<FeatureRuntimeContext> contexts = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        var builder = registry.feature(FEATURE)
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky")
                .surface(ResourceId.of("test", "surface"), "test_surface", "TestSurface",
                        ResourceId.of("test", "coverage"), "test_coverage", "TestCoverage")
                .renderPassContextual(PASS, RenderStage.OVERLAY, context -> {
                    capture(contexts, values, context);
                    return pass();
                })
                .sceneProviderContextual(ResourceId.of("test", "scene"), context -> {
                    capture(contexts, values, context);
                    return new SceneProvider() { };
                })
                .lightProviderContextual(ResourceId.of("test", "light"), context -> {
                    capture(contexts, values, context);
                    return new LightProvider() { };
                })
                .materialSourceContextual(ResourceId.of("test", "material"), context -> {
                    capture(contexts, values, context);
                    return sink -> { };
                });
        builder.register();
        registry.setDefault(Slots.SKY, FEATURE);

        registry.createRuntimeContributions();
        registry.createRuntimeContributions();

        assertEquals(8, contexts.size());
        contexts.subList(1, 4).forEach(context -> assertSame(contexts.getFirst(), context));
        values.subList(1, 4).forEach(value -> assertSame(values.getFirst(), value));
        contexts.subList(5, 8).forEach(context -> assertSame(contexts.get(4), context));
        values.subList(5, 8).forEach(value -> assertSame(values.get(4), value));
        assertNotSame(contexts.getFirst(), contexts.get(4));
        assertNotSame(values.getFirst(), values.get(4));
    }

    @Test
    void rejectsReusingOneKeyIdentityWithAnotherType() {
        FeatureRuntimeContext context = new FeatureRuntimeContext(FEATURE);
        ResourceId id = ResourceId.of("test", "typed");

        context.getOrCreate(new FeatureRuntimeContext.Key<>(id, String.class), () -> "value");

        assertThrows(IllegalStateException.class, () -> context.getOrCreate(
                new FeatureRuntimeContext.Key<>(id, Integer.class), () -> 1));
    }

    private static void capture(List<FeatureRuntimeContext> contexts, List<Object> values,
                                FeatureRuntimeContext context) {
        contexts.add(context);
        values.add(context.getOrCreate(SHARED, Object::new));
    }

    private static CausticaRenderPass pass() {
        return new CausticaRenderPass() {
            @Override public ResourceId id() { return PASS; }
            @Override public RenderStage stage() { return RenderStage.OVERLAY; }
            @Override public void record(PassFrame frame) { }
        };
    }
}
