package dev.comfyfluffy.caustica.api.pass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PassOrderingContractTest {
    @Test
    void passIdsAreValidatedNamespacedValues() {
        PassId id = PassId.of("example.mod", "post/colour-grade");

        assertEquals("example.mod:post/colour-grade", id.toString());
        assertThrows(IllegalArgumentException.class, () -> PassId.of("Example", "post"));
        assertThrows(IllegalArgumentException.class, () -> PassId.of("example", ""));
        assertThrows(NullPointerException.class, () -> PassId.of(null, "post"));
    }

    @Test
    void placementsRetainTheirTypedAnchor() {
        PassId anchor = PassId.of("example", "bloom");

        assertEquals(anchor, PassPlacement.before(anchor).anchor());
        assertEquals(anchor, PassPlacement.after(anchor).anchor());
        assertThrows(NullPointerException.class, () -> PassPlacement.before(null));
        assertThrows(NullPointerException.class, () -> PassPlacement.after(null));
    }
}
