package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validates dot-qualified Slang module and type names used by program definitions. */
final class SlangIdentifier {
    private static final Pattern QUALIFIED_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private SlangIdentifier() { }

    static void require(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!QUALIFIED_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a valid Slang name: " + value);
        }
    }
}
