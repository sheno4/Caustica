package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.CausticaExtension;

/**
 * A Caustica extension that also contributes to the Minecraft-specific host API during discovery.
 * Registration completes before the Minecraft registry is frozen for the rest of the process.
 */
public interface MinecraftApiExtension extends CausticaExtension {
    void registerMinecraft(MinecraftExtensionRegistry registry);
}
