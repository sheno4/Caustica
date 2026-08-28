package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * Validation for the Slang module and type names an extension contributes to program composition.
 *
 * <p>Here rather than on any one caller because surfaces, coverage, environments, and modifiers all name
 * Slang symbols.
 */
final class SlangIdentifier {
    private SlangIdentifier() {
    }

    public static String require(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(label + " is not a Slang identifier: " + value);
        }
        return value;
    }
}
