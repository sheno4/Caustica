package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.nvidia.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetryImpl;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.config.CausticaOptions;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftRtRuntimeStatusTest {
    @TempDir Path temporaryDirectory;
    private CausticaOptions previousStore;
    private CausticaOptions options;

    @BeforeEach
    void installSettings() {
        previousStore = CausticaConfig.store();
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        options = CausticaOptions.load(temporaryDirectory.resolve("caustica.toml"), registry);
        options.apply(CausticaConfig.FEATURE, MinecraftOptions.Rt.ENABLED, false);
        options.apply(CausticaConfig.FEATURE, RendererOptions.Rt.Hdr.ENABLED, false);
        CausticaConfig.install(options);
    }

    @AfterEach
    void restoreSettings() {
        CausticaConfig.install(previousStore);
    }

    @Test
    void inactiveRuntimePublishesDirectStatus() {
        MinecraftRtRuntime runtime = runtime();
        assertEquals(MinecraftRtRuntime.State.OFF, runtime.state());
        assertFalse(runtime.active());
        assertFalse(runtime.frameActive());
        assertFalse(runtime.hasSession());
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

    @Test
    void programmaticHdrChangesReconfigureOncePerChangeWhileRtIsDisabled() {
        MinecraftRtRuntime runtime = runtime();
        AtomicInteger reconfigurations = new AtomicInteger();
        Runnable tick = () -> runtime.tick(null, false, 0,
                MinecraftDimensionKey.of("minecraft", "overworld"), 1280, 720,
                reconfigurations::incrementAndGet);
        runtime.setSwapchainPqAvailable(true);
        assertTrue(runtime.settingAvailable(RendererOptions.Rt.Hdr.ENABLED));
        assertFalse(runtime.hdrEnabled());
        tick.run();
        assertEquals(0, reconfigurations.get());

        options.apply(CausticaConfig.FEATURE, RendererOptions.Rt.Hdr.ENABLED, true);
        tick.run();
        assertEquals(1, reconfigurations.get());
        assertFalse(runtime.active());
        assertFalse(runtime.hasSession());
        assertTrue(runtime.swapchainPqAvailable());
        assertFalse(runtime.hdrEnabled(), "HDR also requires an active PQ swapchain");
        tick.run();
        assertEquals(1, reconfigurations.get());

        runtime.setSwapchainPqActive(true);
        assertTrue(runtime.hdrEnabled());
        options.apply(CausticaConfig.FEATURE, RendererOptions.Rt.Hdr.ENABLED, false);
        tick.run();
        assertEquals(2, reconfigurations.get());
        assertFalse(runtime.hdrEnabled());
        assertTrue(runtime.swapchainPqAvailable(), "preference edits do not change device capability");
        assertTrue(runtime.settingAvailable(RendererOptions.Rt.Hdr.ENABLED));
        tick.run();
        assertEquals(2, reconfigurations.get());
    }

    private MinecraftRtRuntime runtime() {
        return new MinecraftRtRuntime(new RenderSessionHost(options), new MinecraftWorldSessionHost(options),
                new SlangRuntime(new SlangRuntimeConfig(temporaryDirectory.resolve("slang"), Optional.empty())),
                temporaryDirectory.resolve("shaders"), new RtTelemetryImpl(),
                new NgxRuntime.Settings(temporaryDirectory.resolve("ngx"), Optional.empty()));
    }
}
