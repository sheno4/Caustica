package dev.comfyfluffy.caustica.api.pass;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, namespaced identity of one addressable pass within its stage. Live ids are unique within a post
 * or UI stage, but the same value may identify passes in different stages and may be reused after its
 * registration stops recording.
 */
public record PassId(String namespace, String path) implements Comparable<PassId> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/_.-]+");

    public PassId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("invalid pass namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("invalid pass path: " + path);
        }
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
