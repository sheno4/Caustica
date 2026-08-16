package dev.comfyfluffy.caustica.api;

import java.util.Objects;

/** A stable, host-neutral resource name shared by every public engine registry. */
public record ResourceId(String namespace, String path) implements Comparable<ResourceId> {
    public ResourceId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!validNamespace(namespace)) {
            throw new IllegalArgumentException("invalid resource namespace: " + namespace);
        }
        if (!validPath(path)) {
            throw new IllegalArgumentException("invalid resource path: " + path);
        }
    }

    private static boolean validNamespace(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean validPath(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '_' || c == '.' || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }

    public static ResourceId of(String namespace, String path) {
        return new ResourceId(namespace, path);
    }

    public static ResourceId parse(String value) {
        ResourceId id = tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException("invalid resource id: " + value);
        }
        return id;
    }

    public static ResourceId tryParse(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1 || separator != value.lastIndexOf(':')) {
            return null;
        }
        try {
            return new ResourceId(value.substring(0, separator), value.substring(separator + 1));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public int compareTo(ResourceId other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}
