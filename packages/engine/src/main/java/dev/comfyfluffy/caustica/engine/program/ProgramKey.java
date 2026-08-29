package dev.comfyfluffy.caustica.engine.program;

/** Stable identity of one declaration inside one engine program session. */
public record ProgramKey(Kind kind, long sequence) {
    public enum Kind { SURFACE, VOLUME, ENVIRONMENT }
}
