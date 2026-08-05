package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.ShaderSource;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;

public record ComputeProgram(Identifier id, ShaderSource shaderSource, String module, String entryPoint,
                             List<ComputeBinding> bindings, int pushConstantBytes, int maxDispatches,
                             int localSizeX, int localSizeY, int localSizeZ) {
    public ComputeProgram {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(shaderSource, "shaderSource");
        requireSlangIdentifier(module, "module");
        requireSlangIdentifier(entryPoint, "entryPoint");
        bindings = List.copyOf(bindings);
        if (pushConstantBytes < 0 || maxDispatches < 1
                || localSizeX < 1 || localSizeY < 1 || localSizeZ < 1) {
            throw new IllegalArgumentException("invalid compute program dimensions for " + id);
        }
    }

    private static void requireSlangIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(label + " is not a Slang identifier: " + value);
        }
    }
}
