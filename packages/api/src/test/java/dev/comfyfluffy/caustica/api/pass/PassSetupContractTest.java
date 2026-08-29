package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PassSetupContractTest {
    @Test
    void stageSetupsDirectlyExposeGpuServices() throws ReflectiveOperationException {
        assertTrue(PassSetup.class.isInterface());
        assertEquals(GpuDevice.class, PassSetup.class.getMethod("gpu").getReturnType());
        assertTrue(PassSetup.class.isAssignableFrom(WorldResourceSetup.class));
        assertTrue(PassSetup.class.isAssignableFrom(PostEffectSetup.class));
        assertTrue(PassSetup.class.isAssignableFrom(UiSetup.class));
        assertFalse(java.util.Arrays.stream(PostEffectSetup.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("common")));
    }

    @Test
    void uiFrameDerivesExtentAndFormatFromLayer() {
        assertFalse(java.util.Arrays.stream(UiFrame.class.getMethods())
                .anyMatch(method -> method.getName().equals("displayWidth")
                        || method.getName().equals("displayHeight")
                        || method.getName().equals("layerFormat")));
    }

    @Test
    void coherentSceneFactsBelongToEveryPassFrame() throws ReflectiveOperationException {
        assertEquals(dev.comfyfluffy.caustica.api.view.SceneView.class,
                PassFrame.class.getMethod("view").getReturnType());
        assertEquals(double.class, PassFrame.class.getMethod("timeSeconds").getReturnType());
        assertEquals(double.class, PassFrame.class.getMethod("metersPerSceneUnit").getReturnType());
        assertFalse(java.util.Arrays.stream(UiFrame.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("view")));
    }
}
