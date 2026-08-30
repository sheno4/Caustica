package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.nvidia.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetryImpl;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MinecraftRtRuntimeStatusTest {
    @TempDir Path temporaryDirectory;

    @Test
    void inactiveRuntimePublishesDirectStatus() {
        MinecraftRtRuntime runtime = runtime();
        assertEquals(MinecraftRtRuntime.State.OFF, runtime.state());
        assertFalse(runtime.active());
        assertFalse(runtime.frameActive());
        assertFalse(runtime.hasSession());
        assertFalse(runtime.rendererFailed());
        assertEquals(MinecraftRtRuntime.WorldReplacement.FRAME_INACTIVE, runtime.worldReplacement());
    }

    @Test
    void runtimeOwnsBackendAndContextAsInstanceState() throws Exception {
        var backend = MinecraftRtRuntime.class.getDeclaredField("vulkanBackend");
        var context = MinecraftRtRuntime.class.getDeclaredField("vulkanContext");
        var ngxRuntime = MinecraftRtRuntime.class.getDeclaredField("ngxRuntime");

        assertEquals(VulkanRendererBackend.class, backend.getType());
        assertEquals(VulkanDeviceContext.class, context.getType());
        assertEquals(NgxRuntime.class, ngxRuntime.getType());
        assertFalse(Modifier.isStatic(backend.getModifiers()));
        assertFalse(Modifier.isStatic(context.getModifiers()));
        assertFalse(Modifier.isStatic(ngxRuntime.getModifiers()));
    }

    private MinecraftRtRuntime runtime() {
        dev.comfyfluffy.caustica.settings.OptionLookup options = id -> { throw new AssertionError(id); };
        return new MinecraftRtRuntime(new RenderSessionHost(options), new MinecraftWorldSessionHost(options),
                new SlangRuntime(new SlangRuntimeConfig(temporaryDirectory.resolve("slang"), Optional.empty())),
                temporaryDirectory.resolve("shaders"), new RtTelemetryImpl(),
                new NgxRuntime.Settings(temporaryDirectory.resolve("ngx"), Optional.empty()));
    }
}
