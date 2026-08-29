package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * Validates Slang module and type names used by program definitions.
 */
final class SlangIdentifier {
    private SlangIdentifier() {
    }

    public static String requireModule(String value) {
        return requireCompound(value, "module", "\\.");
    }

    public static String requireType(String value) {
        return requireCompound(value, "type", "::");
    }

    private static String requireCompound(String value, String label, String separator) {
        Objects.requireNonNull(value, label);
        String identifier = "[A-Za-z_][A-Za-z0-9_]*";
        if (!value.matches(identifier + "(?:" + separator + identifier + ")*")) {
            throw new IllegalArgumentException(label + " is not a valid Slang name: " + value);
        }
        return value;
    }
}
