package dev.comfyfluffy.caustica.nvidia.nrd;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendDescriptor;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserExtent;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NrdBackendFactoryTest {
    @Test
    void mapsTheSignalContractToTheMatchingNrdMethod() {
        FakeNative nativeApi = new FakeNative();
        NrdBackendFactory factory = new NrdBackendFactory(nativeApi, new NrdDevice(1, 2, 3, 4, 3));
        var descriptor = new DenoiserBackendDescriptor(new DenoiserExtent(1280, 720),
                DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE);
        try (var backend = factory.create(descriptor)) {
            assertEquals(descriptor, backend.descriptor());
            assertEquals(NrdMethod.REBLUR_DIFFUSE_SPECULAR, ((NrdBackend) backend).method());
        }
        factory.close();
        assertThrows(IllegalStateException.class, () -> factory.create(descriptor));
    }

    private static final class FakeNative implements NrdNative {
        @Override public MemorySegment create(MemorySegment description) { return MemorySegment.ofAddress(1); }
        @Override public int resize(MemorySegment instance, int width, int height) { return 0; }
        @Override public int record(MemorySegment instance, long commandBuffer, MemorySegment common,
                                    MemorySegment resources) { return 0; }
        @Override public void destroy(MemorySegment instance) {}
        @Override public String lastError() { return ""; }
    }
}
