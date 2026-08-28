package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/**
 * One surface implementation, its traversal-safe coverage implementation, and their extension-owned data
 * root. A volume is an independent geometry slot registered with {@link ProgramChannel#addVolume}.
 *
 * <p>{@code data} reaches both implementations unchanged. It is commonly a device address for the
 * extension's session material/texture table, but may be any packed 64-bit value. Material variants are
 * extension data indexed from this root or from a geometry's data word; they are not renderer objects.
 * Keep resources reachable from this word alive until the surface's retirement callback runs.
 */
public record SurfaceDefinition(ShaderSource source, String module, String type,
                                String coverageModule, String coverageType, long data) {
    public SurfaceDefinition {
        Objects.requireNonNull(source, "source");
        SlangIdentifier.require(module, "module");
        SlangIdentifier.require(type, "type");
        SlangIdentifier.require(coverageModule, "coverageModule");
        SlangIdentifier.require(coverageType, "coverageType");
    }
}
