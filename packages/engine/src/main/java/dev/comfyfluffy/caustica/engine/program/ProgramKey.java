package dev.comfyfluffy.caustica.engine.program;

/** Stable identity and positive dispatch slot of one declaration kind inside one engine program session. */
public record ProgramKey(Kind kind, int implementationIndex) {
    public ProgramKey {
        if (kind == null) throw new NullPointerException("kind");
        if (implementationIndex <= 0) throw new IllegalArgumentException("implementation index must be positive");
    }

    public enum Kind { SURFACE, VOLUME, ENVIRONMENT }
}
