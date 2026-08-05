package dev.comfyfluffy.caustica.api.provider;

import net.minecraft.resources.Identifier;

public interface LightProvider {
    Identifier id();

    default void prepareFrame() {
    }

    default void onResourceReload() {
    }

    default void shutdown() {
    }
}
