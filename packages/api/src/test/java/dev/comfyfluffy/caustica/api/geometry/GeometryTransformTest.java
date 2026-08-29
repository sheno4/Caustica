package dev.comfyfluffy.caustica.api.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GeometryTransformTest {
    @Test
    void acceptsInvertibleBasis() {
        assertDoesNotThrow(() -> GeometryTransform.translation(1.0, 2.0, 3.0));
    }

    @Test
    void rejectsSingularBasis() {
        assertThrows(IllegalArgumentException.class, () -> new GeometryTransform(
                1, 0, 0,
                2, 0, 0,
                0, 0, 1,
                0, 0, 0));
    }
}
