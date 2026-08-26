package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.shader.ShaderSource;
import dev.comfyfluffy.caustica.api.shader.SlangIdentifier;

import java.util.Objects;

/** A Slang module anchored into the program by name, with no type of its own the engine looks for. */
public record ModuleDefinition(ShaderSource source, String module) {
    public ModuleDefinition {
        Objects.requireNonNull(source, "source");
        SlangIdentifier.require(module, "module");
    }
}
