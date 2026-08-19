package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialProfile;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialVariantOrderingTest {
    @Test
    void profileTopologyAndEmissionProductRetainsItsGpuFacingOrder() {
        OpenPbrMaterialProfile[] profiles = {
                OpenPbrMaterialProfile.ROUGH_DIELECTRIC,
                OpenPbrMaterialProfile.CONDUCTOR,
                OpenPbrMaterialProfile.SMOOTH_DIELECTRIC,
                OpenPbrMaterialProfile.POLISHED_DIELECTRIC,
                OpenPbrMaterialProfile.MEDIUM_ROUGH_DIELECTRIC
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

    /**
     * A scene source picks a profile from the declared set and the registry must already hold a compiled
     * variant for it — {@code MaterialEpochSnapshot.resolve} runs on geometry workers and cannot intern a new one. A
     * profile that exists but was never compiled throws only once a chunk containing that material meshes,
     * which is far too late.
     */
    @Test
    void everyDeclaredProfileHasACompiledVariantSlot() throws ReflectiveOperationException {
        for (Field field : OpenPbrMaterialProfile.class.getFields()) {
            if (field.getType() != OpenPbrMaterialProfile.class) continue;
            OpenPbrMaterialProfile profile = (OpenPbrMaterialProfile) field.get(null);
            assertDoesNotThrow(
                    () -> RtMaterialRegistry.index(profile, MaterialTopology.SURFACE, false),
                    field.getName() + " is declared but has no compiled variant");
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
