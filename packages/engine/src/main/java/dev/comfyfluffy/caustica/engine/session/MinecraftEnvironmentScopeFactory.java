package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.scene.SceneId;

/** Creates one environment-selection scope for one Minecraft contribution and borrowed scene. */
@FunctionalInterface
public interface MinecraftEnvironmentScopeFactory {
    MinecraftEnvironmentScope create(ContributionOwner owner, SceneId scene);
}
