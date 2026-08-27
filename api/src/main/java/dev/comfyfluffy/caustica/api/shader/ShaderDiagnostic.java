package dev.comfyfluffy.caustica.api.shader;

import java.util.Objects;

/** Structured compiler output; line and column are one-based or zero when unavailable. */
public record ShaderDiagnostic(Severity severity, String message, String module, int line, int column) {
    public enum Severity { NOTE, WARNING, ERROR }

    public ShaderDiagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        message = Objects.requireNonNull(message, "message");
        module = module == null ? "" : module;
        if (line < 0 || column < 0) throw new IllegalArgumentException("diagnostic location cannot be negative");
    }
}
