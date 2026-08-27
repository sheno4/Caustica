package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.shader.ShaderCompiler;

/**
 * Immutable services shared by every pass created for one render session.
 *
 * @param gpu session GPU services
 * @param shaders session Slang compiler
 */
public record PassSetup(GpuDevice gpu, ShaderCompiler shaders) {
    public PassSetup {
        if (gpu == null || shaders == null) throw new NullPointerException();
    }
}
