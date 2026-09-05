package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackend;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendDescriptor;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserExtent;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserReset;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;

import java.util.Objects;

/** Owns the fixed three-plane temporal history set and its shared reset state. */
final class RtDenoiserState implements AutoCloseable {
    static final int PLANE_COUNT = 3;

    private final DenoiserBackendFactory factory;
    private RtDenoisingSettings settings;
    private final DenoiserBackend[] backends = new DenoiserBackend[PLANE_COUNT];
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
        if (backends[0] != null && backends[0].descriptor().equals(descriptor)) return;
        closeBackendAfterIdle();
        for (int plane = 0; plane < PLANE_COUNT; plane++) {
            backends[plane] = factory.create(descriptor);
        }
        resetPending = true;
    }

    DenoiserBackend backend() {
        return backend(0);
    }

    DenoiserBackend backend(int plane) {
        if (plane < 0 || plane >= PLANE_COUNT) {
            throw new IllegalArgumentException("temporal denoiser plane must be in [0, " + PLANE_COUNT + ")");
        }
        return Objects.requireNonNull(backends[plane], "temporal denoiser backend is not initialized");
    }

    DenoiserReset frameReset(boolean historyContinuous) {
        return resetPending || !historyContinuous
                ? DenoiserReset.CLEAR_AND_RESTART : DenoiserReset.CONTINUE;
    }

    void frameSubmitted() {
        resetPending = false;
    }

    void resetHistory() {
        resetPending = true;
    }

    void closeBackendAfterIdle() {
        for (int plane = 0; plane < PLANE_COUNT; plane++) {
            if (backends[plane] != null) {
                backends[plane].close();
                backends[plane] = null;
            }
        }
    }

    @Override
    public void close() {
        closeBackendAfterIdle();
    }
}
