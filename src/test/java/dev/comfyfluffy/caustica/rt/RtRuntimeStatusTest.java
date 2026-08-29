package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtRuntimeStatusTest {
    @Test
    void inactiveRuntimePublishesDirectStatus() {
        assertEquals(RtRuntime.State.OFF, RtRuntime.INSTANCE.state());
        assertFalse(RtRuntime.active());
        assertFalse(RtRuntime.frameActive());
        assertFalse(RtRuntime.hasSession());
        assertFalse(RtRuntime.INSTANCE.rendererFailed());
        assertEquals(RtRuntime.WorldReplacement.FRAME_INACTIVE, RtRuntime.INSTANCE.worldReplacement());
    }

    @Test
    void runtimeOwnsBackendAndContextAsInstanceState() throws Exception {
        var backend = RtRuntime.class.getDeclaredField("vulkanBackend");
        var context = RtRuntime.class.getDeclaredField("vulkanContext");

        assertEquals(VulkanRendererBackend.class, backend.getType());
        assertEquals(VulkanDeviceContext.class, context.getType());
        assertFalse(Modifier.isStatic(backend.getModifiers()));
        assertFalse(Modifier.isStatic(context.getModifiers()));
    }
}
