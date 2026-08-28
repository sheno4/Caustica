package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * One volume implementation and its extension-owned session data root.
 *
 * <p>{@code data} reaches the implementation unchanged. It is commonly a device address for a table of
 * density fields and texture descriptors, but may be any packed 64-bit value. Keep resources reachable
 * from this word alive until the volume's retirement callback runs.
 */
public record VolumeDefinition(ShaderSource source, String module, String type, long data) {
    public VolumeDefinition {
        Objects.requireNonNull(source, "source");
        SlangIdentifier.require(module, "module");
        SlangIdentifier.require(type, "type");
    }
}
