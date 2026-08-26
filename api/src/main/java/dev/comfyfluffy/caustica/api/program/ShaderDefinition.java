package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.shader.ShaderSource;
import dev.comfyfluffy.caustica.api.shader.SlangIdentifier;

import java.util.Objects;

/**
 * One Slang type compiled into the world program: where its module resolves from, the module, and the type.
 *
 * <p>The source travels with the definition rather than being held by some enclosing scope, because there
 * is no enclosing scope any more — an extension adds implementations one at a time from wherever it likes,
 * and one constant of its own is cheaper than a registry of who owns what.
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
