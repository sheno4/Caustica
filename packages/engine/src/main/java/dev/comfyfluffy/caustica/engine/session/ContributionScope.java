package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.geometry.MeshPreparer;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;

/**
 * Owner-scoped services and lifecycle controls supplied by the renderer implementation.
 *
 * <p>The session core exposes only the seven service accessors to extension code. The remaining methods are
 * host controls invoked in the API's teardown order. Implementations enforce owner-local identities and
 * release their bookkeeping from {@link #close()} after the contribution's final callback.
 */
public interface ContributionScope extends AutoCloseable {
    GpuDevice gpu();

    GpuComputeQueue compute();

    ProgramChannel program();

    PassChannel passes();

    MeshPreparer meshes();

    SceneChannel scene();

    ResourceFactory resources();

    /** Reject new scoped registrations and compute jobs, then stop and drain active pass callbacks. */
    void quiesce();

    /** Cancel queued compute jobs and logically invalidate every object still owned by this scope. */
    void invalidate();

    /** Drain compute terminals, submissions, frame uses, readiness, retirements, and pass-instance closes. */
    void drain();

    /** Release scope bookkeeping after the contribution's final close callback. */
    @Override
    void close();
}
