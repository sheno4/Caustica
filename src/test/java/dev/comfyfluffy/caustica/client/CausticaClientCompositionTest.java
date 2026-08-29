package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.MinecraftApiBootstrap;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.nvidia.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.rt.RtTelemetryImpl;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CausticaClientCompositionTest {
    @TempDir Path temporaryDirectory;

    @Test
    void publishesOneEagerlyConstructedRootExactlyOnce() {
        RenderSessionHost renderHost = new RenderSessionHost();
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost();
        SlangRuntime slang = new SlangRuntime(new SlangRuntimeConfig(
                temporaryDirectory.resolve("slang"), Optional.empty()));
        Path shaderCache = temporaryDirectory.resolve("shaders");
        NgxRuntime.Settings ngx = new NgxRuntime.Settings(temporaryDirectory.resolve("ngx"), Optional.empty());
        RtTelemetryImpl telemetry = new RtTelemetryImpl();
        MinecraftApiBootstrap.ApiServices services = new MinecraftApiBootstrap.ApiServices(
                renderHost, minecraftHost, new SettingsRegistry(), slang, shaderCache, ngx);
        RtRuntime runtime = new RtRuntime(renderHost, minecraftHost, slang, shaderCache, telemetry, ngx);
        CausticaClientComposition composition = new CausticaClientComposition(runtime, services);

        CausticaClientComposition.publish(composition);

        assertSame(composition, CausticaClientComposition.current());
        assertSame(runtime, CausticaClientComposition.current().runtime());
        assertThrows(IllegalStateException.class,
                () -> CausticaClientComposition.publish(new CausticaClientComposition(runtime, services)));
    }
}
