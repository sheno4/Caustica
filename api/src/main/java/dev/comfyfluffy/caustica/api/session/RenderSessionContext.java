package dev.comfyfluffy.caustica.api.session;

import dev.comfyfluffy.caustica.api.ProviderChannel;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.material.MaterialChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.scene.SceneOwner;
import dev.comfyfluffy.caustica.api.scene.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.scene.light.LightChannel;
import dev.comfyfluffy.caustica.api.shader.ShaderCompiler;

/**
 * Live services and the automatic ownership scope for one render session.
 *
 * <p>Every pass, provider, retained identity, program implementation, material, and owned scene created
 * through these services belongs to this context. Explicit drops remain available for replacement during
 * the session. Merely allocating an identity is enough for the scope to own it. At session end the host
 * performs this order on its session-control thread, without overlapping stop or close calls:
 *
 * <ol>
 * <li>stop starting provider and pass callbacks, wait for callbacks already executing, and reject new
 * scoped registrations;</li>
 * <li>call {@link RenderSessionContribution#stop()} while submissions are still valid;</li>
 * <li>remove pass/provider registrations and drop every object still owned by the scope, including owned
 * scenes;</li>
 * <li>drain accepted submissions and frame uses, run all retirement callbacks, and close the now-drained
 * pass instances;</li>
 * <li>call {@link RenderSessionContribution#close()} before destroying the device.</li>
 * </ol>
 *
 * <p>Objects and identities obtained from a context must not be cached by a process-lived factory.
 * Retirement callbacks complete before contribution close, are serialized by the renderer, and must not
 * block or throw. The host reports an exception and continues draining the remaining callbacks.
 */
public interface RenderSessionContext {
    GpuDevice gpu();

    ShaderCompiler shaderCompiler();

    ProgramChannel program();

    PassChannel passes();

    ProviderChannel providers();

    GeometryChannel geometry();

    MaterialChannel materials();

    LightChannel lights();

    /** Capability for creating scenes owned by this contribution scope. */
    SceneOwner sceneOwner();
}
