package dev.comfyfluffy.caustica.engine.frame;

/** Host-owned scene resources sampled at the runtime tick boundary. */
public record SceneResources(boolean sceneReady, long baseColorAtlasView) {
    public static final SceneResources EMPTY = new SceneResources(false, 0L);
}
