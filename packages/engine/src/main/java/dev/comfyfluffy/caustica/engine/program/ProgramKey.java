package dev.comfyfluffy.caustica.engine.program;

/** Stable identity and positive dispatch slot of one declaration kind inside one engine program session. */
public record ProgramKey(Kind kind, int sequence) {
    public ProgramKey {
        if (kind == null) throw new NullPointerException("kind");
        if (sequence <= 0) throw new IllegalArgumentException("program sequence must be positive");
    }

    public int implementationIndex() {
        return sequence;
    }

    public enum Kind { SURFACE, VOLUME, ENVIRONMENT }
}
