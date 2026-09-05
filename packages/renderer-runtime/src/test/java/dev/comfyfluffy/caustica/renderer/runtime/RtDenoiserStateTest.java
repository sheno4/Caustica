package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackend;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendDescriptor;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserExtent;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserFrame;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserReset;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtDenoiserStateTest {
    private static final DenoiserExtent EXTENT = new DenoiserExtent(1920, 1080);

    @Test
    void rawAndRayReconstructionDoNotCreateTemporalBackends() {
        FakeFactory factory = new FakeFactory();
        RtDenoiserState state = new RtDenoiserState(factory, settings(DenoiserRoute.RAW));
        state.ensureBackend(EXTENT);
        state.configureAfterIdle(settings(DenoiserRoute.RAY_RECONSTRUCTION));
        state.ensureBackend(EXTENT);
        assertEquals(0, factory.created);
    }

    @Test
    void onlyRayReconstructionUsesItsOptimalRenderExtent() {
        assertFalse(RtFrameResources.usesRayReconstructionRenderSize(DenoiserRoute.RAW, true));
        assertFalse(RtFrameResources.usesRayReconstructionRenderSize(DenoiserRoute.TEMPORAL_DENOISER, true));
        assertFalse(RtFrameResources.usesRayReconstructionRenderSize(DenoiserRoute.RAY_RECONSTRUCTION, false));
        assertTrue(RtFrameResources.usesRayReconstructionRenderSize(DenoiserRoute.RAY_RECONSTRUCTION, true));
    }

    @Test
    void onlyTemporalDenoisingUsesTheStandaloneUpscalerExtent() {
        assertFalse(RtFrameResources.usesTemporalUpscalerRenderSize(DenoiserRoute.RAW, true));
        assertFalse(RtFrameResources.usesTemporalUpscalerRenderSize(DenoiserRoute.RAY_RECONSTRUCTION, true));
        assertFalse(RtFrameResources.usesTemporalUpscalerRenderSize(DenoiserRoute.TEMPORAL_DENOISER, false));
        assertTrue(RtFrameResources.usesTemporalUpscalerRenderSize(DenoiserRoute.TEMPORAL_DENOISER, true));
    }

    @Test
    void previousViewUsesCurrentMinusPreviousCameraTranslation() {
        Matrix4f previous = RtReconstruction.nrdPreviousWorldToView(false,
                new Matrix4f(), new Matrix4f(), 3.0f, -2.0f, 1.0f);
        assertEquals(3.0f, previous.m30());
        assertEquals(-2.0f, previous.m31());
        assertEquals(1.0f, previous.m32());

        Matrix4f reset = RtReconstruction.nrdPreviousWorldToView(true,
                new Matrix4f().translation(7.0f, 8.0f, 9.0f), new Matrix4f(), 3.0f, -2.0f, 1.0f);
        assertEquals(7.0f, reset.m30());
        assertEquals(8.0f, reset.m31());
        assertEquals(9.0f, reset.m32());
    }

    @Test
    void denoisingRangeIncludesPrimaryHitsButExcludesTheSkySentinel() {
        assertTrue(RtReconstruction.NRD_DENOISING_RANGE > 10_000.0f);
        assertTrue(RtReconstruction.NRD_DENOISING_RANGE < 65_504.0f);
    }

    @Test
    void temporalBackendIsFixedToExtentAndRecreatedAfterResize() {
        FakeFactory factory = new FakeFactory();
        RtDenoiserState state = new RtDenoiserState(factory, settings(DenoiserRoute.TEMPORAL_DENOISER));
        state.ensureBackend(EXTENT);
        FakeBackend first = factory.last;
        state.ensureBackend(EXTENT);
        assertEquals(RtDenoiserState.PLANE_COUNT, factory.created);

        state.ensureBackend(new DenoiserExtent(1280, 720));
        assertTrue(first.closed);
        assertEquals(RtDenoiserState.PLANE_COUNT * 2, factory.created);
        assertEquals(new DenoiserExtent(1280, 720), state.backend().descriptor().extent());
    }

    @Test
    void ownsIndependentTemporalHistoryForEachStablePlane() {
        FakeFactory factory = new FakeFactory();
        RtDenoiserState state = new RtDenoiserState(factory, settings(DenoiserRoute.TEMPORAL_DENOISER));
        state.ensureBackend(EXTENT);

        assertEquals(RtDenoiserState.PLANE_COUNT, factory.backends.size());
        for (int plane = 0; plane < RtDenoiserState.PLANE_COUNT; plane++) {
            assertSame(factory.backends.get(plane), state.backend(plane));
        }
    }

    @Test
    void resetsOnCreationDiscontinuityExplicitResetAndRouteChange() {
        FakeFactory factory = new FakeFactory();
        RtDenoiserState state = new RtDenoiserState(factory, settings(DenoiserRoute.TEMPORAL_DENOISER));
        state.ensureBackend(EXTENT);
        assertEquals(DenoiserReset.CLEAR_AND_RESTART, state.frameReset(true));
        state.frameSubmitted();
        assertEquals(DenoiserReset.CONTINUE, state.frameReset(true));
        assertEquals(DenoiserReset.CLEAR_AND_RESTART, state.frameReset(false));
        state.frameSubmitted();
        state.resetHistory();
        assertEquals(DenoiserReset.CLEAR_AND_RESTART, state.frameReset(true));

        FakeBackend first = factory.last;
        state.configureAfterIdle(new RtDenoisingSettings(DenoiserRoute.TEMPORAL_DENOISER,
                DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE));
        assertTrue(first.closed);
        state.ensureBackend(EXTENT);
        assertEquals(DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE,
                state.backend().descriptor().signalEncoding());
        assertEquals(DenoiserReset.CLEAR_AND_RESTART, state.frameReset(true));
    }

    @Test
    void closeReleasesBackendButNotDeviceScopedFactory() {
        FakeFactory factory = new FakeFactory();
        RtDenoiserState state = new RtDenoiserState(factory, settings(DenoiserRoute.TEMPORAL_DENOISER));
        state.ensureBackend(EXTENT);
        List<FakeBackend> backends = List.copyOf(factory.backends);
        assertSame(backends.get(0), state.backend());
        state.close();
        assertTrue(backends.stream().allMatch(backend -> backend.closed));
        assertFalse(factory.closed);
    }

    private static RtDenoisingSettings settings(DenoiserRoute route) {
        return new RtDenoisingSettings(route, DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE);
    }

    private static final class FakeFactory implements DenoiserBackendFactory {
        private int created;
        private FakeBackend last;
        private final List<FakeBackend> backends = new ArrayList<>();
        private boolean closed;

        @Override
        public DenoiserBackend create(DenoiserBackendDescriptor descriptor) {
            created++;
            last = new FakeBackend(descriptor);
            backends.add(last);
            return last;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeBackend implements DenoiserBackend {
        private final DenoiserBackendDescriptor descriptor;
        private boolean closed;

        private FakeBackend(DenoiserBackendDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public DenoiserBackendDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void record(DenoiserFrame frame) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
