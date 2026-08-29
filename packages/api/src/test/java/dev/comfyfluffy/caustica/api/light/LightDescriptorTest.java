package dev.comfyfluffy.caustica.api.light;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LightDescriptorTest {
    @Test
    void exposesOnlyTheProvenFiniteShapes() {
        assertEquals(Set.of(LightDescriptor.Rectangle.class, LightDescriptor.Spot.class),
                Set.of(LightDescriptor.Finite.class.getPermittedSubclasses()));
    }

    @Test
    void acceptsCanonicalLights() {
        assertDoesNotThrow(() -> new LightDescriptor.Rectangle(
                0, 0, 0, 1, 0, 0, 0, 1, 0, 2, 3, 4));
        assertDoesNotThrow(() -> new LightDescriptor.Spot(
                0, 0, 0, 0, 0, -1, 10, 0.5, 1, 2, 3));
        assertDoesNotThrow(() -> new LightDescriptor.Distant(0, 1, 0, 1, 2, 3, 0, false));
    }

    @Test
    void rejectsNanAndNegativePhotometry() {
        assertThrows(IllegalArgumentException.class,
                () -> new LightDescriptor.Spot(Double.NaN, 0, 0, 0, 0, -1, 10, 0.5, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LightDescriptor.Spot(0, 0, 0, 0, 0, -1, 10, 0.5, -1, 1, 1));
    }

    @Test
    void rejectsInvalidOrientationAndAngles() {
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Distant(
                0, 2, 0, 1, 1, 1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Spot(
                0, 0, 0, 0, 0, -2, 10, 0.5, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Spot(
                0, 0, 0, 0, 0, -1, 10, 0, 1, 1, 1));
    }

    @Test
    void rejectsDegenerateRectangle() {
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Rectangle(
                0, 0, 0, 1, 0, 0, 2, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Rectangle(
                0, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1));
    }
}
