package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class SceneContractTest {
    private interface Environment { }

    @Test
    void sceneReferenceCarriesNoAdministrationAuthority() {
        assertEquals(0, SceneId.class.getDeclaredMethods().length);
        assertFalse(AutoCloseable.class.isAssignableFrom(SceneId.class));
    }

    @Test
    void environmentBindingUsesTheSharedDataVocabulary() throws NoSuchMethodException {
        assertSame(ShaderData.class,
                EnvironmentBinding.class.getMethod("bindingData").getReturnType());
        ShaderDataType<Environment> environment = ShaderDataType.create("environment");
        EnvironmentBinding<Environment> binding = new EnvironmentBinding<>(
                new EnvironmentId<>() { }, environment.data(3L));
        assertSame(environment, binding.bindingData().type());
    }

    @Test
    void viewKeepsTheOpaqueSceneSeparateFromCameraState() {
        assertSame(SceneId.class, SceneView.class.getRecordComponents()[0].getType());
        assertSame(Camera.class, SceneView.class.getRecordComponents()[1].getType());
    }
}
