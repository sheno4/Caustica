package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;

/** Host-owned environment binding for the scene borrowed by one Minecraft world epoch. */
@FunctionalInterface
public interface MinecraftEnvironmentSelector {
    /** Replace the borrowed scene's environment at the host's next retained publication boundary. */
    void select(EnvironmentBinding<?> binding);
}
