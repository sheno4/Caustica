package dev.comfyfluffy.caustica.minecraft.api;

import java.util.Objects;

/** Process-scoped Minecraft session registration supplied by the host. */
public record MinecraftApi(MinecraftWorldSessionChannel sessions) {
    public static final String ENTRYPOINT = "caustica-minecraft";

    public MinecraftApi {
        Objects.requireNonNull(sessions, "sessions");
    }
}
