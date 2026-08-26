package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectPass;
import dev.comfyfluffy.caustica.api.shader.ShaderSource;
import dev.comfyfluffy.caustica.api.scene.SceneProvider;
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
                .shaderSource(ShaderSource.classpath(Object.class, "/test/shaders"))
                .environment(ResourceId.of("test", "sky"), "test_sky", "TestSky")
                .surface(ResourceId.of("test", "surface"), "test_surface", "TestSurface",
                        ResourceId.of("test", "coverage"), "test_coverage", "TestCoverage")
                .postEffectPass(PASS, context -> {
                    capture(contexts, values, context);
                    return pass();
                })
                .sceneProvider(ResourceId.of("test", "scene"), context -> {
                    capture(contexts, values, context);
                    return new SceneProvider() { };
                });
        builder.register();

        registry.createRuntimeContributions();
        registry.createRuntimeContributions();

        assertEquals(4, contexts.size());
        assertSame(contexts.getFirst(), contexts.get(1));
        assertSame(values.getFirst(), values.get(1));
        assertSame(contexts.get(2), contexts.get(3));
        assertSame(values.get(2), values.get(3));
        assertNotSame(contexts.getFirst(), contexts.get(2));
        assertNotSame(values.getFirst(), values.get(2));
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

    private static PostEffectPass pass() {
        return new PostEffectPass() {
            @Override public ResourceId id() { return PASS; }
            @Override public void record(PostEffectFrame frame) { }
        };
    }
}
