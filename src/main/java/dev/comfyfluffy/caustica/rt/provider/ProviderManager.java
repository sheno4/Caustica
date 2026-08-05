package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ProviderManager {
    public static final ProviderManager INSTANCE = new ProviderManager(null, null, null);

    private final Set<ProviderKey> disabled = new HashSet<>();
    private final Map<Identifier, SceneProvider> scenes;
    private final Map<Identifier, LightProvider> lights;
    private final Map<Identifier, MaterialSource> materials;

    ProviderManager(Map<Identifier, SceneProvider> scenes, Map<Identifier, LightProvider> lights,
                    Map<Identifier, MaterialSource> materials) {
        this.scenes = scenes;
        this.lights = lights;
        this.materials = materials;
    }

    public void updateScenes() {
        invoke("scene", scenes(), SceneProvider::update, SceneProvider::shutdown);
    }

    public void prepareFrame() {
        invoke("scene", scenes(), SceneProvider::prepareFrame, SceneProvider::shutdown);
        invoke("light", lights(), LightProvider::prepareFrame, LightProvider::shutdown);
    }

    public void invalidateScenes() {
        invoke("scene", scenes(), SceneProvider::invalidate, SceneProvider::shutdown);
    }

    public void onResourceReload() {
        invoke("scene", scenes(), SceneProvider::onResourceReload, SceneProvider::shutdown);
        invoke("light", lights(), LightProvider::onResourceReload, LightProvider::shutdown);
        invoke("material", materials(), MaterialSource::onResourceReload, MaterialSource::shutdown);
    }

    public void shutdown() {
        invokeAll("scene", scenes(), SceneProvider::shutdown);
        invokeAll("light", lights(), LightProvider::shutdown);
        invokeAll("material", materials(), MaterialSource::shutdown);
    }

    private Map<Identifier, SceneProvider> scenes() {
        return scenes != null ? scenes : CausticaApi.registry().sceneProviders();
    }

    private Map<Identifier, LightProvider> lights() {
        return lights != null ? lights : CausticaApi.registry().lightProviders();
    }

    private Map<Identifier, MaterialSource> materials() {
        return materials != null ? materials : CausticaApi.registry().materialSources();
    }

    private <T> void invoke(String kind, Map<Identifier, T> providers, Consumer<T> action, Consumer<T> cleanup) {
        for (Map.Entry<Identifier, T> entry : providers.entrySet()) {
            ProviderKey key = new ProviderKey(kind, entry.getKey());
            if (disabled.contains(key)) {
                continue;
            }
            try {
                action.accept(entry.getValue());
            } catch (Throwable t) {
                disabled.add(key);
                CausticaMod.LOGGER.error("Caustica {} provider {} failed and was disabled", kind, entry.getKey(), t);
                try {
                    cleanup.accept(entry.getValue());
                } catch (Throwable cleanupFailure) {
                    CausticaMod.LOGGER.error("Caustica {} provider {} cleanup failed", kind, entry.getKey(),
                            cleanupFailure);
                }
            }
        }
    }

    private <T> void invokeAll(String kind, Map<Identifier, T> providers, Consumer<T> action) {
        for (Map.Entry<Identifier, T> entry : providers.entrySet()) {
            try {
                action.accept(entry.getValue());
            } catch (Throwable t) {
                CausticaMod.LOGGER.error("Caustica {} provider {} failed during shutdown", kind, entry.getKey(), t);
            }
        }
    }

    private record ProviderKey(String kind, Identifier id) {
    }
}
