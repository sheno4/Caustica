package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.minecraft.MinecraftApiBootstrap;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.platform.CausticaPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CausticaMod {
    public static final String MOD_ID = "caustica";
    public static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    private CausticaMod() {
    }

    public static void initialize() {
        CausticaConfig.configure(CausticaPlatform.current().configDir());
        // Register every setting (applying TOML file values) and write a default config on first run.
        CausticaConfig.ensureRegistered();
        CausticaConfig.saveIfMissing();
        MinecraftApiBootstrap.initialize();
        LOGGER.info("Caustica initialized (common); config: {}", CausticaConfig.configPath());
    }
}
