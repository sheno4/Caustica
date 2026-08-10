package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.ProviderId;

public final class MinecraftLightProvider implements LightProvider {
    public static final ProviderId ID = ProviderId.of("caustica", "minecraft_lights");
}
