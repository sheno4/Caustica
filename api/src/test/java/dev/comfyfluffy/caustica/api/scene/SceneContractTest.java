package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SceneContractTest {
    @Test
    void owningCapabilityIsNotADataReference() {
        assertFalse(SceneId.class.isAssignableFrom(SceneHandle.class));
    }

    @Test
    void sceneScaleMustBeFiniteAndPositive() {
        SceneEnvironment environment = SceneEnvironment.of(new EnvironmentId() { }, 0L);
        assertThrows(IllegalArgumentException.class,
                () -> new SceneDefinition(environment, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SceneDefinition(environment, Double.NaN));
    }
}
