package dev.comfyfluffy.caustica.api.session;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;

/**
 * Live services and the automatic ownership scope for one render session.
 *
 * <p>Every pass, retained identity, program implementation, and owned scene created
 * through these services belongs to this context. Explicit drops remain available for replacement during
 * the session. Merely allocating an identity is enough for the scope to own it. At session end the host
 * performs this order on its session-control thread, without overlapping stop or close calls:
 *
 * <ol>
 * <li>stop starting pass callbacks, wait for callbacks already executing, and reject new scoped
 * registrations;</li>
 * <li>call {@link RenderSessionContribution#stop()} while submissions are still valid;</li>
 * <li>remove pass registrations and logically invalidate every object still owned by the scope,
 * including owned scenes, at a renderer publication boundary. Scene and mesh cascades remove their
 * placements; stale surface and environment references resolve to visible error implementations. Logical
 * references never pin an owner or make teardown wait for another contribution;</li>
 * <li>cancel pending program tickets, drain accepted submissions and frame uses, run all ticket
 * callbacks, run all retirement callbacks, and close the now-drained pass instances;</li>
 * <li>call {@link RenderSessionContribution#close()} before destroying the device.</li>
 * </ol>
 *
 * <p>{@link dev.comfyfluffy.caustica.api.scene.SceneId SceneId} and
 * {@link dev.comfyfluffy.caustica.api.program.EnvironmentId EnvironmentId} may be shared across
 * contributions within this render session. Other operations may name only identities issued through the same
 * context. The issuer owns mutation and removal; copying an id never extends its lifetime. A shared environment
 * id grants no authority over a scene; only its {@link dev.comfyfluffy.caustica.api.scene.SceneHandle} does.
 * Objects and identities obtained from a context must not be cached by a process-lived factory.
 * Retirement callbacks complete before contribution close, are serialized by the renderer, and must not
 * block or throw. The host reports an exception and continues draining the remaining callbacks.
 */
public interface RenderSessionContext {
    GpuDevice gpu();

    ProgramChannel program();

    PassChannel passes();

    GeometryChannel geometry();

    LightChannel lights();

    /** Session-scoped creation and administration entry point for independently retained scenes. */
    SceneChannel scenes();
}
