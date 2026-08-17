package dev.comfyfluffy.caustica.api.provider;

/**
 * Immutable semantic material view for one compiled resource epoch. Scene workers may retain this
 * snapshot until their work finishes; renderer binding and texture indices are intentionally absent.
 */
public interface MaterialSnapshot {
    long epoch();

    /**
     * Resolve source-neutral material semantics. Implementations return an epoch-cached value and do
     * not allocate, so callers may use this while building every mesh primitive.
     */
    MaterialAnalysis analyze(SceneMesh.MaterialReference material);
}
