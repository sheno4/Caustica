package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.material.MaterialChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.scene.light.LightChannel;
import dev.comfyfluffy.caustica.api.shader.ShaderCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CausticaApiTest {
    @Test
    void exposesTheHostInstalledChannelsAndRefusesASecondInstall() {
        ProgramChannel program = stub(ProgramChannel.class);
        PassChannel passes = stub(PassChannel.class);
        ProviderChannel providers = stub(ProviderChannel.class);
        RendererChannels channels = new RendererChannels() {
            @Override public GpuDevice gpu() { return null; }
            @Override public ShaderCompiler shaderCompiler() { return null; }
            @Override public ProgramChannel program() { return program; }
            @Override public PassChannel passes() { return passes; }
            @Override public ProviderChannel providers() { return providers; }
            @Override public SceneChannel scenes() { return null; }
            @Override public GeometryChannel geometry() { return null; }
            @Override public MaterialChannel materials() { return null; }
            @Override public LightChannel lights() { return null; }
        };
        CausticaBootstrap.install(channels);

        CausticaApi api = CausticaApi.getInstance();

        assertSame(program, api.program());
        assertSame(passes, api.passes());
        assertSame(providers, api.providers());
        assertThrows(IllegalStateException.class, () -> CausticaBootstrap.install(channels));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> null);
    }
}
