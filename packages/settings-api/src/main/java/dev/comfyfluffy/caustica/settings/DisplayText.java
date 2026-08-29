package dev.comfyfluffy.caustica.settings;

import java.util.Objects;

/** Host-neutral display text that a host UI can render as a literal or localized string. */
public record DisplayText(Kind kind, String value) {
    public static final DisplayText EMPTY = literal("");

    public DisplayText {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
    }

    public static DisplayText literal(String value) {
        return new DisplayText(Kind.LITERAL, value);
    }

    public static DisplayText translatable(String key) {
        return new DisplayText(Kind.TRANSLATABLE, key);
    }

    public enum Kind {
        LITERAL,
        TRANSLATABLE
    }
}
