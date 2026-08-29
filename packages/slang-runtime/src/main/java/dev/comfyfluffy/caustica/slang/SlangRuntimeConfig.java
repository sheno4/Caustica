package dev.comfyfluffy.caustica.slang;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Filesystem inputs used to locate or extract one process-scoped Slang compiler runtime. */
public record SlangRuntimeConfig(Path extractionRoot, Optional<Path> runtimeOverride) {
    public SlangRuntimeConfig {
        extractionRoot = Objects.requireNonNull(extractionRoot, "extractionRoot")
                .toAbsolutePath().normalize();
        runtimeOverride = Objects.requireNonNull(runtimeOverride, "runtimeOverride")
                .map(path -> path.toAbsolutePath().normalize());
    }
}
