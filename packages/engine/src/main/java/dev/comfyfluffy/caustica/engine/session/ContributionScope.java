package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.resource.ResourceChannel;

/**
 * Owner-scoped services and lifecycle controls supplied by the renderer implementation.
 *
 * <p>The session core exposes only the six service accessors to extension code. The remaining methods are
 * host controls invoked in the API's teardown order. Implementations enforce owner-local identities and
 * release their bookkeeping from {@link #close()} after the contribution's final callback.
 */
public interface ContributionScope extends AutoCloseable {
    GpuDevice gpu();

    ProgramChannel program();

    PassChannel passes();

    GeometryChannel geometry();

    LightChannel lights();

    ResourceChannel resources();

    /** Reject new scoped registrations, stop future pass callbacks, and wait for callbacks already running. */
    void quiesce();

    /** Remove pass registrations and logically invalidate every object still owned by this scope. */
    void invalidate();

    /** Drain accepted submissions, frame uses, program readiness, retirements, and pass-instance closes. */
    void drain();

    /** Release scope bookkeeping after the contribution's final close callback. */
    @Override
    void close();
}
