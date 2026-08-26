package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.shader.ShaderSource;
import dev.comfyfluffy.caustica.api.shader.SlangIdentifier;

import java.util.Objects;

/**
 * A surface implementation and its coverage type, which are added and dropped as one because a material
 * selects both with a single index.
 */
public record SurfaceDefinition(ShaderSource source, String module, String type,
                                String coverageModule, String coverageType) {
    public SurfaceDefinition {
        Objects.requireNonNull(source, "source");
        SlangIdentifier.require(module, "module");
        SlangIdentifier.require(type, "type");
        SlangIdentifier.require(coverageModule, "coverageModule");
        SlangIdentifier.require(coverageType, "coverageType");
    }
}
