package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.LightProvider;
import net.minecraft.resources.Identifier;

public final class MinecraftLightProvider implements LightProvider {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("caustica", "minecraft_lights");

    @Override
    public Identifier id() {
        return ID;
    }
}
