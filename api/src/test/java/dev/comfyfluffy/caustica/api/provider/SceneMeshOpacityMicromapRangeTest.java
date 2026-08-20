package dev.comfyfluffy.caustica.api.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SceneMeshOpacityMicromapRangeTest {
    @Test
    void acceptsOrderedUnitCoverageBounds() {
        assertDoesNotThrow(() -> new SceneMesh.OpacityMicromapRange(0.0f, 1.0f));
        assertDoesNotThrow(() -> new SceneMesh.OpacityMicromapRange(0.4f, 0.4f));
    }

    @Test
    void rejectsNonConservativeRangeShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> new SceneMesh.OpacityMicromapRange(-0.01f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new SceneMesh.OpacityMicromapRange(0.2f, 1.01f));
        assertThrows(IllegalArgumentException.class,
                () -> new SceneMesh.OpacityMicromapRange(0.8f, 0.2f));
        assertThrows(IllegalArgumentException.class,
                () -> new SceneMesh.OpacityMicromapRange(Float.NaN, 1.0f));
    }
}
