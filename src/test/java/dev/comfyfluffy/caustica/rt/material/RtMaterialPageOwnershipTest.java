package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialPageOwnershipTest {
    @Test
    void partialPageConstructionDestroysOwnedTexturesInReverseOrder() {
        RtMaterialPageCompiler.OwnedResources<String> owned = new RtMaterialPageCompiler.OwnedResources<>();
        List<String> destroyed = new ArrayList<>();

        try {
            owned.own("surface0");
            owned.own("normal");
            throw new IllegalStateException("surface1 allocation failed");
        } catch (IllegalStateException expected) {
            owned.destroy(destroyed::add);
        }

        assertEquals(List.of("normal", "surface0"), destroyed);
    }

    @Test
    void successfulPageConstructionTransfersOwnershipWithoutDestroyingTextures() {
        RtMaterialPageCompiler.OwnedResources<String> owned = new RtMaterialPageCompiler.OwnedResources<>();
        List<String> destroyed = new ArrayList<>();
        owned.own("surface0");
        owned.own("normal");

        owned.transfer();
        owned.destroy(destroyed::add);

        assertTrue(destroyed.isEmpty());
    }
}
