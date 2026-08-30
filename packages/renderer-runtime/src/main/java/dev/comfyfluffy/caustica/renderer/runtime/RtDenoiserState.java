package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackend;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendDescriptor;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserExtent;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserReset;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;

import java.util.Objects;

/** Owns the temporal backend and reset state inside one renderer lifetime. */
final class RtDenoiserState implements AutoCloseable {
    private final DenoiserBackendFactory factory;
    private RtDenoisingSettings settings;
    private DenoiserBackend backend;
    private boolean resetPending = true;

    RtDenoiserState(DenoiserBackendFactory factory, RtDenoisingSettings settings) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    RtDenoisingSettings settings() {
        return settings;
    }

    boolean configured(RtDenoisingSettings candidate) {
        return settings.equals(candidate);
    }

    void configureAfterIdle(RtDenoisingSettings settings) {
        settings = Objects.requireNonNull(settings, "settings");
        if (this.settings.equals(settings)) return;
        closeBackendAfterIdle();
        this.settings = settings;
        resetPending = true;
    }

    void ensureBackend(DenoiserExtent extent) {
        Objects.requireNonNull(extent, "extent");
        if (settings.route() != DenoiserRoute.TEMPORAL_DENOISER) return;
        DenoiserBackendDescriptor descriptor = new DenoiserBackendDescriptor(extent, settings.signalEncoding());
        if (backend != null && backend.descriptor().equals(descriptor)) return;
        closeBackendAfterIdle();
        backend = factory.create(descriptor);
        resetPending = true;
    }

    DenoiserBackend backend() {
        return Objects.requireNonNull(backend, "temporal denoiser backend is not initialized");
    }

    DenoiserReset frameReset(boolean historyContinuous) {
        return resetPending || !historyContinuous
                ? DenoiserReset.CLEAR_AND_RESTART : DenoiserReset.CONTINUE;
    }

    void frameRecorded() {
        resetPending = false;
    }

    void resetHistory() {
        resetPending = true;
    }

    void closeBackendAfterIdle() {
        if (backend != null) {
            backend.close();
            backend = null;
        }
    }

    @Override
    public void close() {
        closeBackendAfterIdle();
    }
}
