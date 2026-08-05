package dev.comfyfluffy.caustica.api.provider;

import net.minecraft.resources.Identifier;

public interface SceneProvider {
    Identifier id();

    default void update() {
    }

    default void prepareFrame() {
    }

    default void invalidate() {
    }

    default void onResourceReload() {
    }

    default void shutdown() {
    }
}
