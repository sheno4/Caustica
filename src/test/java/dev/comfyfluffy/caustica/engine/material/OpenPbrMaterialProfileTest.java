package dev.comfyfluffy.caustica.engine.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OpenPbrMaterialProfileTest {
    @Test
    void storesOpenPbrPerceptualRoughnessAndMetalness() {
        OpenPbrMaterialProfile profile = new OpenPbrMaterialProfile(0.35f, 0.8f);
        assertEquals(0.35f, profile.specularRoughness());
        assertEquals(0.8f, profile.baseMetalness());
    }

    @Test
    void rejectsValuesOutsideTheSupportedUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new OpenPbrMaterialProfile(-0.1f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new OpenPbrMaterialProfile(0.5f, 1.1f));
        assertThrows(IllegalArgumentException.class, () -> new OpenPbrMaterialProfile(Float.NaN, 0.0f));
    }
}
