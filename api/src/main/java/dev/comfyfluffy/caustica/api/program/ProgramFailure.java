package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/** Diagnostic data for a rejected shader composition. */
public record ProgramFailure(String summary, String diagnostics) {
    public ProgramFailure {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (summary.isBlank()) throw new IllegalArgumentException("summary must not be blank");
    }
}
