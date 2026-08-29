package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * One Slang type compiled into the world program: where its module resolves from, the compound module name,
 * and the namespace-qualified type name.
 *
 * <p>The source travels with the definition because implementations are added independently from wherever
 * an extension owns them; no enclosing declaration scope supplies one.
 *
 * <p>Public implementation types should live under an extension-unique namespace. Qualification prevents
 * declarations from colliding when independently owned modules are composed. Several registrations may
 * deliberately reuse the same qualified shader type and distinguish behavior through their data words.
 */
public record ShaderDefinition(ShaderSource source, String module, String type) {
    public ShaderDefinition {
        Objects.requireNonNull(source, "source");
        SlangIdentifier.requireModule(module);
        SlangIdentifier.requireType(type);
    }
}
