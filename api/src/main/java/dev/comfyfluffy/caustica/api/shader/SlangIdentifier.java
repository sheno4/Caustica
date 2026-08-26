package dev.comfyfluffy.caustica.api.shader;

import java.util.Objects;

/**
 * Validation for the Slang module and type names an extension hands the compiler.
 *
 * <p>Here rather than on any one of its callers: surface implementations, environment implementations,
 * emission profiles and pass resource modules all name Slang symbols, and none of them owns the rule.
 */
public final class SlangIdentifier {
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
