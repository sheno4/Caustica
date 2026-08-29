package dev.comfyfluffy.caustica.minecraft.api;

/** Optional process entry point for an extension that consumes Minecraft world-session facts. */
@FunctionalInterface
public interface MinecraftExtension {
    void registerMinecraft(MinecraftApi api);
}
