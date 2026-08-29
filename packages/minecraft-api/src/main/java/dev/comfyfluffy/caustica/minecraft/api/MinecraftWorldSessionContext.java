package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;

/** Borrowed renderer and Minecraft facts fixed for one client-world/dimension epoch. */
public interface MinecraftWorldSessionContext {
    /** Owner-scoped core services borrowed for this contribution's world-session lifetime. */
    RenderSessionContext renderSession();

    /** Host-owned scene for this exact client-world epoch. It grants selection, not creation authority. */
    SceneId scene();

    MinecraftDimensionKey dimension();

    /** Resources already applied when the contribution opens. */
    ResourcePackEpoch resourcePackEpoch();

    /** The only host scene mutation exposed here: selecting this epoch's environment implementation. */
    MinecraftEnvironmentSelector environment();
}
