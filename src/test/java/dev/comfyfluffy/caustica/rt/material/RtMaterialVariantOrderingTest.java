package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialProfile;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialVariantOrderingTest {
    @Test
    void profileTopologyAndEmissionProductRetainsItsGpuFacingOrder() {
        OpenPbrMaterialProfile[] profiles = {
                OpenPbrMaterialProfile.ROUGH_DIELECTRIC,
                OpenPbrMaterialProfile.CONDUCTOR,
                OpenPbrMaterialProfile.SMOOTH_DIELECTRIC,
                OpenPbrMaterialProfile.POLISHED_DIELECTRIC
        };
        int expected = 0;
        for (OpenPbrMaterialProfile profile : profiles) {
            for (MaterialTopology topology : MaterialTopology.values()) {
                for (boolean emitting : new boolean[]{false, true}) {
                    assertEquals(expected++, RtMaterialRegistry.index(profile, topology, emitting));
                }
            }
        }
    }

    @Test
    void publicTopologyMapsToTheExistingTransportValues() {
        assertEquals(RtMaterialRegistry.TRANSPORT_SURFACE,
                RtMaterialRegistry.transport(MaterialTopology.SURFACE));
        assertEquals(RtMaterialRegistry.TRANSPORT_MEDIUM_BOUNDARY,
                RtMaterialRegistry.transport(MaterialTopology.MEDIUM_BOUNDARY));
    }
}
