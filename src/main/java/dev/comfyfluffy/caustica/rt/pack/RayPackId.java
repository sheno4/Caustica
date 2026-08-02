package dev.comfyfluffy.caustica.rt.pack;

import java.util.Objects;
import java.util.regex.Pattern;

public record RayPackId(String namespace, String name) {
    private static final Pattern PART = Pattern.compile("[a-z0-9][a-z0-9_.-]*");

    public RayPackId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        if (!PART.matcher(namespace).matches() || !PART.matcher(name).matches()) {
            throw new IllegalArgumentException("ray-pack id must be lowercase namespace:name");
        }
    }

    public static RayPackId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
            throw new IllegalArgumentException("ray-pack id must be namespace:name");
        }
        return new RayPackId(value.substring(0, separator), value.substring(separator + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + name;
    }
}
