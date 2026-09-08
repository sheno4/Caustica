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
    private interface Sky { }

    @Test
    void sceneReferenceCarriesNoAdministrationAuthority() {
        assertEquals(0, SceneId.class.getDeclaredMethods().length);
        assertFalse(AutoCloseable.class.isAssignableFrom(SceneId.class));
    }

    @Test
    void environmentBindingUsesTheSharedDataVocabulary() throws NoSuchMethodException {
        assertSame(ShaderData.class,
                EnvironmentBinding.class.getMethod("bindingData").getReturnType());
        ShaderDataType<Sky> sky = ShaderDataType.create("sky");
        EnvironmentBinding<Sky> binding = new EnvironmentBinding<>(
                new EnvironmentId<>() { }, sky.data(3L));
        assertSame(sky, binding.bindingData().type());
    }

    @Test
    void viewKeepsTheOpaqueSceneSeparateFromCameraState() {
        assertSame(SceneId.class, SceneView.class.getRecordComponents()[0].getType());
        assertSame(Camera.class, SceneView.class.getRecordComponents()[1].getType());
    }
}
