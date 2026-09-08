package dev.comfyfluffy.caustica.api.pass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PassOrderingContractTest {
    @Test
    void passIdsAreValidatedNamespacedValues() {
        PassId id = new PassId("example.mod", "post/colour-grade");

        assertEquals("example.mod:post/colour-grade", id.toString());
        assertEquals("mod_2.test-name:post/grade_2.test-name",
                new PassId("mod_2.test-name", "post/grade_2.test-name").toString());
        assertThrows(IllegalArgumentException.class, () -> new PassId("example/mod", "post"));
        assertThrows(IllegalArgumentException.class, () -> new PassId("example", "post\n"));
        assertThrows(IllegalArgumentException.class, () -> new PassId("example", "post effect"));
        assertThrows(IllegalArgumentException.class, () -> new PassId("Example", "post"));
        assertThrows(IllegalArgumentException.class, () -> new PassId("example", ""));
        assertThrows(NullPointerException.class, () -> new PassId(null, "post"));
    }

    @Test
    void placementsRetainTheirTypedAnchor() {
        PassId anchor = new PassId("example", "bloom");

        assertEquals(anchor, PassPlacement.before(anchor).anchor());
        assertEquals(anchor, PassPlacement.after(anchor).anchor());
        assertThrows(NullPointerException.class, () -> PassPlacement.before(null));
        assertThrows(NullPointerException.class, () -> PassPlacement.after(null));
    }
}
