package dev.comfyfluffy.caustica.minecraft.adapter.session;

/** Receives an isolated Minecraft world-contribution failure. */
@FunctionalInterface
public interface MinecraftSessionFailureHandler {
    void report(MinecraftSessionFailure failure);
}
