package dev.comfyfluffy.caustica.minecraft.adapter.session;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import dev.comfyfluffy.caustica.engine.session.EnvironmentSelectionScope;

/** Creates one environment-selection scope for one Minecraft contribution and borrowed scene. */
@FunctionalInterface
public interface MinecraftEnvironmentScopeFactory {
    EnvironmentSelectionScope create(ContributionOwner owner, SceneId scene);
}
