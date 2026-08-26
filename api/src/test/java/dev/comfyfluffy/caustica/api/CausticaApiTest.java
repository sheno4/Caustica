package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.material.MaterialChannel;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.scene.light.LightChannel;
import dev.comfyfluffy.caustica.api.option.Option;
import dev.comfyfluffy.caustica.api.option.OptionValues;
import dev.comfyfluffy.caustica.api.shader.ShaderSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CausticaApiTest {
    @Test
    void exposesOneHostInstalledApiInstance() {
        CausticaRegistry registry = new CausticaRegistry();
        ResourceId environment = ResourceId.of("test", "environment");
        registry.feature(environment)
                .shaderSource(ShaderSource.classpath(CausticaApiTest.class, "/test-shaders"))
                .environment(environment, "test_environment", "TestEnvironment")
                .surface(ResourceId.of("test", "surface"), "test_surface", "TestSurface",
                        ResourceId.of("test", "coverage"), "test_coverage", "TestCoverage")
                .register();
        OptionValues defaults = new OptionValues() {
            @Override
            public <T> T get(Option<T> option) {
                return option.defaultValue();
            }
        };
        SceneChannel sceneChannel = null;
        GeometryChannel geometryChannel = null;
        MaterialChannel materialChannel = null;
        LightChannel lightChannel = null;
        RendererChannels channels = new RendererChannels() {
            @Override public dev.comfyfluffy.caustica.api.gpu.GpuDevice gpu() { return null; }
            @Override public SceneChannel scenes() { return sceneChannel; }
            @Override public GeometryChannel geometry() { return geometryChannel; }
            @Override public MaterialChannel materials() { return materialChannel; }
            @Override public LightChannel lights() { return lightChannel; }
        };
        CausticaApi.initialize(registry, ignored -> defaults, channels);

        CausticaApi api = CausticaApi.getInstance();

        assertSame(registry, api.registry());
        assertEquals(true, api.options(ResourceId.of("test", "feature")).get(Option.bool("enabled", true)));
        assertThrows(IllegalStateException.class,
                () -> CausticaApi.initialize(new CausticaRegistry(), ignored -> defaults, channels));
    }
}
