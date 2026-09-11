package dev.comfyfluffy.caustica.minecraft.client.session;

/** Receives an isolated Minecraft world-contribution failure. */
@FunctionalInterface
public interface MinecraftSessionFailureHandler {
    void report(MinecraftSessionFailure failure);
}
