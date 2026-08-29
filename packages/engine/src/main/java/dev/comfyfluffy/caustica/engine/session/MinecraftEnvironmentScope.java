package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.minecraft.api.MinecraftEnvironmentSelector;

/** Owner-scoped environment selection and retirement controls used by the Minecraft host adapter. */
public interface MinecraftEnvironmentScope extends MinecraftEnvironmentSelector {
    void invalidate();
    void drain();
}
