package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.shader.ShaderSource;
import dev.comfyfluffy.caustica.api.shader.SlangIdentifier;

import java.util.Objects;

/**
 * One Slang type compiled into the world program: where its module resolves from, the module, and the type.
 *
 * <p>The source travels with the definition because implementations are added independently from wherever
 * an extension owns them; no enclosing declaration scope supplies one.
 *
 * <p>Type names are global to the composition: two live implementations may not declare the same type from
 * different modules, and that is checked when the operation is submitted.
 */
public record ShaderDefinition(ShaderSource source, String module, String type) {
    public ShaderDefinition {
        Objects.requireNonNull(source, "source");
        SlangIdentifier.require(module, "module");
        SlangIdentifier.require(type, "type");
    }
}
