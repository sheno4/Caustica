package dev.comfyfluffy.caustica.engine.pass;

/** Stable renderer identity for one accepted pass registration. */
public record PassKey(long sequence, Stage stage) {
    public enum Stage {
        WORLD_RESOURCE,
        POST_EFFECT,
        UI
    }
}
