package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.settings.ResourceId;

import java.util.Objects;

/** Loader-neutral identity of the Minecraft dimension for one client-world epoch. */
public record MinecraftDimensionKey(ResourceId id) {
    public MinecraftDimensionKey {
        Objects.requireNonNull(id, "id");
    }

    public static MinecraftDimensionKey of(String namespace, String path) {
        return new MinecraftDimensionKey(ResourceId.of(namespace, path));
    }
}
