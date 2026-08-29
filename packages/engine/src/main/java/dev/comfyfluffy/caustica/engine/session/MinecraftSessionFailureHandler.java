package dev.comfyfluffy.caustica.engine.session;

/** Receives an isolated Minecraft world-contribution failure. */
@FunctionalInterface
public interface MinecraftSessionFailureHandler {
    void report(MinecraftSessionFailure failure);
}
