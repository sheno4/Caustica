package dev.comfyfluffy.caustica.api.provider;

import net.minecraft.resources.Identifier;

public interface MaterialSource {
    Identifier id();

    default void onResourceReload() {
    }

    default void shutdown() {
    }
}
