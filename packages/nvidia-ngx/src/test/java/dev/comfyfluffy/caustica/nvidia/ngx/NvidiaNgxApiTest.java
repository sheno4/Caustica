package dev.comfyfluffy.caustica.nvidia.ngx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NvidiaNgxApiTest {
    @Test
    void settingsAreImmutableFeatureInputs() {
        assertEquals(2, new DlssRayReconstruction.Settings(true, 2, 0).quality());
        assertFalse(new DlssFrameGeneration.Settings(false).enabled());
    }

    @Test
    void classifiesTheNgxFailureRange() {
        assertTrue(NgxRuntime.ngxFailed(0xBAD00001));
        assertFalse(NgxRuntime.ngxFailed(0));
    }
}
