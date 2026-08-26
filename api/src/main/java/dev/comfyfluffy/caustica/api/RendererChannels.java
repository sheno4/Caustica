package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.material.MaterialChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.shader.ShaderCompiler;
import dev.comfyfluffy.caustica.api.scene.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.scene.light.LightChannel;

/**
 * The renderer's channels, installed once by the host adapter. Extensions reach them through
 * {@link CausticaApi} and never name this type.
 *
 * <p>This is a host service-provider interface. Extension entry points use {@link CausticaApi}; only the
 * adapter that owns renderer bootstrap implements this type and installs it through
 * {@link CausticaBootstrap}.
 */
public interface RendererChannels {
    GpuDevice gpu();

    ShaderCompiler shaderCompiler();

    ProgramChannel program();

    PassChannel passes();

    ProviderChannel providers();

    SceneChannel scenes();

    GeometryChannel geometry();

    MaterialChannel materials();

    LightChannel lights();
}
