package dev.comfyfluffy.caustica.api;

import net.minecraft.resources.Identifier;

import java.util.Objects;

public record Slot(Identifier id, String interfaceModule, String interfaceType) {
    public Slot {
        Objects.requireNonNull(id, "id");
        requireSlangIdentifier(interfaceModule, "interfaceModule");
        requireSlangIdentifier(interfaceType, "interfaceType");
    }

    static String requireSlangIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(label + " is not a Slang identifier: " + value);
        }
        return value;
    }
}
