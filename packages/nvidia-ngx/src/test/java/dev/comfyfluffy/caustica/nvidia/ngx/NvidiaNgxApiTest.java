package dev.comfyfluffy.caustica.nvidia.ngx;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NvidiaNgxApiTest {
    @Test
    void runtimeRequiresADeviceContext() {
        var settings = new NgxRuntime.Settings(Path.of("ngx-data"), Optional.empty());
        var failure = assertThrows(NullPointerException.class, () -> new NgxRuntime(null, settings));
        assertEquals("context", failure.getMessage());
    }

    @Test
    void runtimeSettingsResolvePathsBeforeNativeInitialization() {
        var data = Path.of("ngx-data", "..", "ngx-cache");
        var shim = Path.of("native", "..", "ngxshim.dll");
        var settings = new NgxRuntime.Settings(data, Optional.of(shim));
        assertEquals(data.toAbsolutePath().normalize(), settings.dataDirectory());
        assertEquals(Optional.of(shim.toAbsolutePath().normalize()), settings.shimOverride());
    }

    @Test
    void settingsAreImmutableFeatureInputs() {
        assertEquals(2, new DlssRayReconstruction.Settings(true, 2, 0).quality());
        assertEquals(2, new DlssSuperResolution.Settings(true, 2, 11).quality());
        assertFalse(new DlssFrameGeneration.Settings(false).enabled());
    }

    @Test
    void rayReconstructionRejectsUnsupportedNgxModes() {
        assertThrows(IllegalArgumentException.class, () -> new DlssRayReconstruction.Settings(true, 4, 5));
        assertThrows(IllegalArgumentException.class, () -> new DlssRayReconstruction.Settings(true, 1, 6));
    }

    @Test
    void superResolutionRejectsUnsupportedNgxModes() {
        assertThrows(IllegalArgumentException.class, () -> new DlssSuperResolution.Settings(true, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> new DlssSuperResolution.Settings(true, 6, 0));
        assertThrows(IllegalArgumentException.class, () -> new DlssSuperResolution.Settings(true, 2, 9));
    }

    @Test
    void classifiesTheNgxFailureRange() {
        assertTrue(NgxRuntime.ngxFailed(0xBAD00001));
        assertFalse(NgxRuntime.ngxFailed(0));
    }
}
