package dev.comfyfluffy.caustica.api.provider;

import java.util.Objects;

/**
 * Lazily opened canonical CPU pixels and their compile resolution. {@code alphaFrameCount} is exhaustive
 * for the resource epoch; zero makes no conservative temporal-alpha claim. Analysis consumers depend
 * only on this source and do not need GPU placement or semantic-stream declarations.
 */
public record MaterialTextureAnalysisSource(int width, int height, int alphaFrameCount,
                                            MaterialTextureSource texture) {
    public MaterialTextureAnalysisSource {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("material dimensions must be positive");
        }
        if (alphaFrameCount < 0) {
            throw new IllegalArgumentException("alpha frame count must be non-negative");
        }
        Objects.requireNonNull(texture, "texture");
    }
}
