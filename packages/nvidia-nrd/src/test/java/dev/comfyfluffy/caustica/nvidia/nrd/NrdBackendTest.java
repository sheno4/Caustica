package dev.comfyfluffy.caustica.nvidia.nrd;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserCommonSettings;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserExtent;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserFrame;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserImage;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserInputs;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserReset;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NrdBackendTest {
    @Test
    void ownsOnlyNativeSessionAndRecordsBorrowedFrame() {
        FakeNative nativeApi = new FakeNative();
        NrdBackend backend = new NrdBackend(nativeApi, new NrdDevice(1, 2, 3, 4, 3),
                NrdMethod.RELAX_DIFFUSE_SPECULAR, 640, 360);
        backend.record(frame());
        backend.close();
        assertEquals(1, nativeApi.creates);
        assertEquals(DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE,
                backend.descriptor().signalEncoding());
        assertEquals(0, nativeApi.resizes);
        assertEquals(1, nativeApi.records);
        assertEquals(1, nativeApi.destroys);
        assertThrows(IllegalStateException.class, () -> backend.record(frame()));
    }

    @Test
    void rejectsValuesThatDoNotFitTheNativeIntegration() {
        assertThrows(IllegalArgumentException.class, () -> new NrdDevice(1, 2, 3, 0, 256));
        assertThrows(IllegalArgumentException.class, () -> new NrdBackend(new FakeNative(),
                new NrdDevice(1, 2, 3, 0, 1), NrdMethod.RELAX_DIFFUSE_SPECULAR, 65_536, 1));
        NrdBackend backend = new NrdBackend(new FakeNative(), new NrdDevice(1, 2, 3, 0, 1),
                NrdMethod.REBLUR_DIFFUSE_SPECULAR, 1, 1);
        assertEquals(DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE,
                backend.descriptor().signalEncoding());
        assertThrows(IllegalArgumentException.class, () -> backend.record(frame()));
        backend.close();
    }

    private static DenoiserFrame frame() {
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        var common = new DenoiserCommonSettings(identity, identity, identity, identity,
                0, 0, 0, 0, 1, 1, 0, 100, .01f, .02f, 16, 1,
                false, false, false, DenoiserReset.CONTINUE);
        DenoiserImage image = new DenoiserImage(10, 97, 1, new DenoiserExtent(640, 360));
        return new DenoiserFrame(55, common, new DenoiserInputs(image, image, image, image, image, image, image,
                Optional.empty(), Optional.empty()));
    }

    private static final class FakeNative implements NrdNative {
        int creates, resizes, records, destroys;
        @Override public MemorySegment create(MemorySegment description) { creates++; return MemorySegment.ofAddress(1); }
        @Override public int resize(MemorySegment instance, int width, int height) { resizes++; return 0; }
        @Override public int record(MemorySegment instance, long commandBuffer, MemorySegment common, MemorySegment resources) { records++; return 0; }
        @Override public void destroy(MemorySegment instance) { destroys++; }
        @Override public String lastError() { return ""; }
    }
}
