package dev.comfyfluffy.caustica.api.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LightDescriptorTest {
    @Test
    void acceptsCanonicalLights() {
        assertDoesNotThrow(() -> new LightDescriptor.Rectangle(
                0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 2, 3, 4));
        assertDoesNotThrow(() -> new LightDescriptor.Point(0, 0, 0, 10, 1, 2, 3));
        assertDoesNotThrow(() -> new LightDescriptor.Spot(
                0, 0, 0, 0, 0, -1, 0, 1, 0, 10, 0.5, 0.4, 1, 2, 3));
        assertDoesNotThrow(() -> new LightDescriptor.Distant(0, 1, 0, 1, 2, 3, 0));
    }

    @Test
    void rejectsNanAndNegativePhotometry() {
        assertThrows(IllegalArgumentException.class,
                () -> new LightDescriptor.Point(Double.NaN, 0, 0, 10, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LightDescriptor.Point(0, 0, 0, 10, -1, 1, 1));
    }

    @Test
    void rejectsInvalidOrientationAndAngles() {
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Distant(
                0, 2, 0, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Spot(
                0, 0, 0, 0, 0, -1, 0, 0, -1, 10, 0.5, 0.4, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Spot(
                0, 0, 0, 0, 0, -1, 0, 1, 0, 10, 0, 0.4, 1, 1, 1));
    }

    @Test
    void rejectsDegenerateOrMisorientedRectangle() {
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Rectangle(
                0, 0, 0, 1, 0, 0, 2, 0, 0, 0, 0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LightDescriptor.Rectangle(
                0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, -1, 1, 1, 1));
    }
}
