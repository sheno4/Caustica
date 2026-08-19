package dev.comfyfluffy.caustica.api.provider;

/**
 * A thread-safe retained-geometry publisher valid for one active scene-provider instance. Submissions are
 * copied before the call returns and become eligible for publication at the next host scene-update cadence.
 */
public interface SceneScope extends SceneGeometrySink {
}
