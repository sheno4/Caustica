package dev.comfyfluffy.caustica.api.pass;

import java.util.Objects;

public record ComputeBinding(String name, ComputeImageKind kind) {
    public ComputeBinding {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("binding name is not a Slang identifier: " + name);
        }
    }

    public static ComputeBinding storage(String name) {
        return new ComputeBinding(name, ComputeImageKind.STORAGE);
    }

    public static ComputeBinding sampledLinear(String name) {
        return new ComputeBinding(name, ComputeImageKind.SAMPLED_LINEAR);
    }
}
