package dev.comfyfluffy.caustica.api.session;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;

/**
 * Live services and the automatic ownership scope for one render session.
 *
 * <p>Every pass, retained mutation identity, program registration, and resource generation created through
 * these services belongs to this contribution context. Explicit closes and drops remain available for
 * replacement during the session. Merely allocating an identity is enough for the scope to own it. At session end the host
 * performs this order on its session-control thread, without overlapping stop or close calls:
 *
 * <ol>
 * <li>stop starting pass callbacks, wait for callbacks already executing, and reject new scoped
 * registrations;</li>
 * <li>call {@link RenderSessionContribution#stop()} while submissions are still valid;</li>
 * <li>remove pass registrations and logically invalidate every object still owned by the contribution at
 * a renderer publication boundary. Mesh removal cascades to its placements; stale surface and environment
 * references resolve to visible error implementations, and stale volumes resolve to vacuum. Logical
 * references never pin an owner or make teardown wait for another contribution;</li>
 * <li>cancel pending program registrations, drain accepted submissions and frame uses, run all readiness
 * callbacks, run all retirement callbacks, and close the now-drained pass instances;</li>
 * <li>call {@link RenderSessionContribution#close()} before destroying the device.</li>
 * </ol>
 *
 * <p>{@link dev.comfyfluffy.caustica.api.geometry.MeshId MeshId},
 * {@link dev.comfyfluffy.caustica.api.geometry.InstanceId InstanceId}, and
 * {@link dev.comfyfluffy.caustica.api.light.LightId LightId} are owner-local mutation identities. Only the
 * contribution whose context issued one may set, replace, or drop it.
 * {@link dev.comfyfluffy.caustica.api.scene.SceneId SceneId},
 * {@link dev.comfyfluffy.caustica.api.program.SurfaceId SurfaceId},
 * {@link dev.comfyfluffy.caustica.api.program.VolumeId VolumeId}, and
 * {@link dev.comfyfluffy.caustica.api.program.EnvironmentId EnvironmentId} are same-session, non-owning
 * selection references. A contribution may name a reference explicitly handed to it by another
 * contribution, but gains no removal authority and does not extend the issuer's lifetime. References from
 * another render session are always invalid.
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

    ResourceFactory resources();
}
