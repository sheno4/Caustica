package dev.comfyfluffy.caustica.minecraft.api;

import java.util.Objects;
import dev.comfyfluffy.caustica.settings.SettingsAccess;

/** Process-scoped Minecraft capabilities and loaded extension options supplied by the host. */
public record MinecraftApi(MinecraftWorldSessionChannel sessions, SettingsAccess options) {
    public static final String ENTRYPOINT = "caustica-minecraft";

    public MinecraftApi {
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(options, "options");
    }
}
