package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import dev.comfyfluffy.caustica.renderer.runtime.RtDenoisingSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftDenoisingRouteTest {
    @Test
    void mapsEachConfigurationValueToOneRoute() {
        assertEquals(DenoiserRoute.RAW,
                MinecraftRtRuntime.denoisingSettings("raw", "relax").route());
        assertEquals(DenoiserRoute.TEMPORAL_DENOISER,
                MinecraftRtRuntime.denoisingSettings("temporal_denoiser", "relax").route());
        assertEquals(DenoiserRoute.RAY_RECONSTRUCTION,
                MinecraftRtRuntime.denoisingSettings("ray_reconstruction", "relax").route());
    }

    @Test
    void methodOnlyChangesTheTemporalSignalEncoding() {
        assertEquals(DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE,
                MinecraftRtRuntime.denoisingSettings("temporal_denoiser", "relax").signalEncoding());
        assertEquals(DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE,
                MinecraftRtRuntime.denoisingSettings("temporal_denoiser", "reblur").signalEncoding());
        assertEquals(DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE,
                MinecraftRtRuntime.denoisingSettings("ray_reconstruction", "reblur").signalEncoding());
    }

    @Test
    void standaloneSuperResolutionBelongsOnlyToTheTemporalRoute() {
        RtDenoisingSettings temporal = MinecraftRtRuntime.denoisingSettings("temporal_denoiser", "reblur");
        RtDenoisingSettings rayReconstruction = MinecraftRtRuntime.denoisingSettings("ray_reconstruction", "reblur");
        RtDenoisingSettings raw = MinecraftRtRuntime.denoisingSettings("raw", "reblur");

        assertTrue(MinecraftRtRuntime.superResolutionSettings(temporal, 2, 11).enabled());
        assertFalse(MinecraftRtRuntime.superResolutionSettings(rayReconstruction, 2, 11).enabled());
        assertFalse(MinecraftRtRuntime.superResolutionSettings(raw, 2, 11).enabled());
        assertEquals(11, MinecraftRtRuntime.superResolutionSettings(temporal, 2, 11).preset());
    }
}
