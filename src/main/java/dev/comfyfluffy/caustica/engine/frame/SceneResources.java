package dev.comfyfluffy.caustica.engine.frame;

/** Host scene readiness sampled at the runtime tick boundary. */
public record SceneResources(boolean sceneReady) {
    public static final SceneResources EMPTY = new SceneResources(false);
}
