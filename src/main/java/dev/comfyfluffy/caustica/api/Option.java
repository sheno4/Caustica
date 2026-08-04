package dev.comfyfluffy.caustica.api;

import java.util.List;
import java.util.Objects;

public record Option<T>(String id, Kind kind, T defaultValue, Double minimum, Double maximum,
                        List<T> choices, Reload reload) {
    public enum Kind {
        BOOL,
        RANGE,
        ENUM,
        COLOR
    }

    public Option {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(choices, "choices");
        Objects.requireNonNull(reload, "reload");
        choices = List.copyOf(choices);
        if (!id.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid option id: " + id);
        }
        if (kind == Kind.RANGE && (minimum == null || maximum == null || minimum > maximum)) {
            throw new IllegalArgumentException("range option requires an ordered minimum and maximum");
        }
        if (kind == Kind.ENUM && (choices.isEmpty() || !choices.contains(defaultValue))) {
            throw new IllegalArgumentException("enum option choices must contain its default");
        }
    }

    public static Option<Boolean> bool(String id, boolean defaultValue) {
        return new Option<>(id, Kind.BOOL, defaultValue, null, null, List.of(), Reload.LOOK);
    }

    public static Option<Float> range(String id, float minimum, float maximum, float defaultValue) {
        if (defaultValue < minimum || defaultValue > maximum) {
            throw new IllegalArgumentException("range default is outside its bounds");
        }
        return new Option<>(id, Kind.RANGE, defaultValue, (double) minimum, (double) maximum,
                List.of(), Reload.LOOK);
    }

    public static <E extends Enum<E>> Option<E> enumOf(String id, E defaultValue, List<E> choices) {
        return new Option<>(id, Kind.ENUM, defaultValue, null, null, choices, Reload.LOOK);
    }

    public static Option<Integer> color(String id, int defaultRgb) {
        return new Option<>(id, Kind.COLOR, defaultRgb, null, null, List.of(), Reload.LOOK);
    }

    public Option<T> reload(Reload reload) {
        return new Option<>(id, kind, defaultValue, minimum, maximum, choices, reload);
    }
}
