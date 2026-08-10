package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.ResourceId;

public final class MinecraftLightProvider implements LightProvider {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_lights");
}
