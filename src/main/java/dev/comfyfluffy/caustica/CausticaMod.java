package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.minecraft.MinecraftApiBootstrap;
import dev.comfyfluffy.caustica.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.rt.RtTelemetryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CausticaMod {
    public static final String MOD_ID = "caustica";
    public static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    private CausticaMod() {
    }

    public static void initialize(CausticaPlatform platform) {
        CausticaConfig.configure(platform.configDir());
        // Register every setting (applying TOML file values) and write a default config on first run.
        CausticaConfig.ensureRegistered();
        CausticaConfig.saveIfMissing();
        RtTelemetryImpl telemetry = new RtTelemetryImpl();
        MinecraftApiBootstrap.ApiServices apiServices = MinecraftApiBootstrap.initialize(platform, telemetry);
        RtRuntime runtime = new RtRuntime(apiServices.renderSessionHost(), apiServices.minecraftWorldSessionHost(),
                apiServices.slangRuntime(), apiServices.shaderCacheRoot(), telemetry, apiServices.ngxSettings());
        CausticaClientComposition.publish(new CausticaClientComposition(runtime, apiServices));
        LOGGER.info("Caustica initialized (common); config: {}", CausticaConfig.configPath());
    }
}
