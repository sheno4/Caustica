package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/** Stable diagnostic for a rejected shader composition; it is data rather than an engine exception. */
public record ProgramFailure(String summary, String diagnostics) {
    public ProgramFailure {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (summary.isBlank()) throw new IllegalArgumentException("summary must not be blank");
    }
}
