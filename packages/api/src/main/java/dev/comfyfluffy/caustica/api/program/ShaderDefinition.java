package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * One Slang type compiled into the world program: where its module resolves from, the compound module name,
 * and the namespace-qualified type name.
 *
 * <p>Each definition carries the source used to resolve its module.
 *
 * <p>Public implementation types should live under an extension-unique namespace. Qualification prevents
 * declarations from colliding when independently owned modules are composed. Several registrations may
 * reuse the same qualified shader type and distinguish behavior through their data words.
 */
public record ShaderDefinition(ShaderSource source, String module, String type) {
    public ShaderDefinition {
        Objects.requireNonNull(source, "source");
        SlangIdentifier.requireModule(module);
        SlangIdentifier.requireType(type);
    }
}
