package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.retained.RetainedPublication;

/** One contribution's environment selection slot for the scene borrowed by a Minecraft world epoch. */
@FunctionalInterface
public interface MinecraftEnvironmentSelector {
    /**
     * Replaces this contribution's selection and makes it the borrowed scene's active environment.
     * The latest successful selection has precedence, including a reselection by an existing owner.
     * Removing a contribution restores the most recently selected surviving contribution.
     */
    RetainedPublication select(EnvironmentBinding<?> binding);
}
