package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SceneContractTest {
    private interface Sky { }

    @Test
    void owningCapabilityIsNotADataReference() {
        assertFalse(SceneId.class.isAssignableFrom(SceneHandle.class));
    }

    @Test
    void sceneScaleMustBeFiniteAndPositive() {
        ShaderDataType<Sky> sky = ShaderDataType.create("sky");
        EnvironmentBinding<Sky> environment = EnvironmentBinding.of(
                new EnvironmentId<>() { }, sky.data(0L));
        assertThrows(IllegalArgumentException.class,
                () -> new SceneDefinition(environment, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SceneDefinition(environment, Double.NaN));
    }

    @Test
    void environmentBindingUsesTheSharedDataVocabulary() throws NoSuchMethodException {
        assertFalse(EnvironmentBinding.class.isAssignableFrom(SceneHandle.class));
        org.junit.jupiter.api.Assertions.assertSame(ShaderData.class,
                EnvironmentBinding.class.getMethod("bindingData").getReturnType());
        org.junit.jupiter.api.Assertions.assertSame(EnvironmentBinding.class,
                SceneDefinition.class.getMethod("environment").getReturnType());
    }
}
