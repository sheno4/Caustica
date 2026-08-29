package dev.comfyfluffy.caustica.api.pass;

import java.util.Objects;

/**
 * Stable, namespaced identity of one addressable pass within its stage. Live ids are unique within a post
 * or UI stage, but the same value may identify passes in different stages and may be reused after its
 * registration stops recording.
 */
public record PassId(String namespace, String path) implements Comparable<PassId> {
    public PassId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!validNamespace(namespace)) {
            throw new IllegalArgumentException("invalid pass namespace: " + namespace);
        }
        if (!validPath(path)) {
            throw new IllegalArgumentException("invalid pass path: " + path);
        }
    }

    public static PassId of(String namespace, String path) {
        return new PassId(namespace, path);
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

    @Override
    public int compareTo(PassId other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        return namespaceOrder != 0 ? namespaceOrder : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}
