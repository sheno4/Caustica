package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialVariantOrderingTest {
    @Test
    void profileTransmissionAndEmissionProductRetainsItsGpuFacingOrder() {
        OpenPbrMaterialProfile[] profiles = {
                OpenPbrMaterialProfile.ROUGH_DIELECTRIC,
                OpenPbrMaterialProfile.CONDUCTOR,
                OpenPbrMaterialProfile.SMOOTH_DIELECTRIC,
                OpenPbrMaterialProfile.POLISHED_DIELECTRIC
        };
        int expected = 0;
        for (OpenPbrMaterialProfile profile : profiles) {
            for (boolean transmissive : new boolean[]{false, true}) {
                for (boolean emitting : new boolean[]{false, true}) {
                    assertEquals(expected++, RtMaterialRegistry.index(profile, transmissive, emitting));
                }
            }
        }
    }
}
