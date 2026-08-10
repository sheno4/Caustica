package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.LightSink;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.ProviderId;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ProviderManager {
    public static final ProviderManager INSTANCE = new ProviderManager(null, null, null);

    // Placeholder: nothing reads a submitted light yet — see LightSink's own javadoc. Kept as a real
    // sink (not a null check at the call site) so a provider can be written and tested against the
    // eventual shape now.
    private static final LightSink FAKE_LIGHT_SINK = (id, dirX, dirY, dirZ, illuminanceLux) -> {
    };

    // Provider failures disable it for the process. Normal session shutdown is tracked separately:
    // providers are lazy/restartable and receive callbacks again after beginSession().
    private final Set<ProviderKey> failed = new HashSet<>();
    private final Set<ProviderKey> stoppedThisSession = new HashSet<>();
    private final Set<ProviderKey> shutDownThisSession = new HashSet<>();
    private final Map<ProviderId, SceneProvider> scenes;
    private final Map<ProviderId, LightProvider> lights;
    private final Map<ProviderId, MaterialSource> materials;

    ProviderManager(Map<ProviderId, SceneProvider> scenes, Map<ProviderId, LightProvider> lights,
                    Map<ProviderId, MaterialSource> materials) {
        this.scenes = scenes;
        this.lights = lights;
        this.materials = materials;
    }

    /** Begin a new RT session; normally stopped providers become eligible for callbacks again. */
    public void beginSession() {
        stoppedThisSession.clear();
        shutDownThisSession.clear();
    }

    public void updateScenes() {
        invoke("scene", scenes(), SceneProvider::update, SceneProvider::stop);
    }

    public void prepareFrame() {
        invoke("scene", scenes(), SceneProvider::prepareFrame, SceneProvider::stop);
        invoke("light", lights(), LightProvider::prepareFrame, LightProvider::stop);
        invoke("light", lights(), provider -> provider.submitLights(FAKE_LIGHT_SINK), LightProvider::stop);
    }

    public void invalidateScenes() {
        invoke("scene", scenes(), SceneProvider::invalidate, SceneProvider::stop);
    }

    public void onResourceReload() {
        invoke("scene", scenes(), SceneProvider::onResourceReload, SceneProvider::stop);
        invoke("light", lights(), LightProvider::onResourceReload, LightProvider::stop);
        invoke("material", materials(), MaterialSource::onResourceReload, MaterialSource::stop);
    }

    /** Stop every provider before session GPU work is drained. No GPU owner is released in this phase. */
    public void stopProviders() {
        stopRemaining("scene", scenes(), SceneProvider::stop);
        stopRemaining("light", lights(), LightProvider::stop);
        stopRemaining("material", materials(), MaterialSource::stop);
    }

    /** Release every stopped provider after session GPU work is idle. */
    public void shutdownResources() {
        shutdownStopped("scene", scenes(), SceneProvider::shutdown);
        shutdownStopped("light", lights(), LightProvider::shutdown);
        shutdownStopped("material", materials(), MaterialSource::shutdown);
    }

    private Map<ProviderId, SceneProvider> scenes() {
        return scenes != null ? scenes : CausticaApi.registry().sceneProviders();
    }

    private Map<ProviderId, LightProvider> lights() {
        return lights != null ? lights : CausticaApi.registry().lightProviders();
    }

    private Map<ProviderId, MaterialSource> materials() {
        return materials != null ? materials : CausticaApi.registry().materialSources();
    }

    private <T> void invoke(String kind, Map<ProviderId, T> providers, Consumer<T> action, Consumer<T> stop) {
        for (Map.Entry<ProviderId, T> entry : providers.entrySet()) {
            ProviderKey key = new ProviderKey(kind, entry.getKey());
            if (failed.contains(key) || stoppedThisSession.contains(key)) {
                continue;
            }
            try {
                action.accept(entry.getValue());
            } catch (Throwable t) {
                failed.add(key);
                CausticaMod.LOGGER.error("Caustica {} provider {} failed and was disabled", kind, entry.getKey(), t);
                stopOne(kind, entry, key, stop);
            }
        }
    }

    private <T> void stopRemaining(String kind, Map<ProviderId, T> providers, Consumer<T> action) {
        for (Map.Entry<ProviderId, T> entry : providers.entrySet()) {
            ProviderKey key = new ProviderKey(kind, entry.getKey());
            if (failed.contains(key) && !stoppedThisSession.contains(key)) {
                continue;
            }
            stopOne(kind, entry, key, action);
        }
    }

    private <T> void stopOne(String kind, Map.Entry<ProviderId, T> entry, ProviderKey key, Consumer<T> action) {
        if (!stoppedThisSession.add(key)) {
            return;
        }
        try {
            action.accept(entry.getValue());
        } catch (Throwable t) {
            failed.add(key);
            CausticaMod.LOGGER.error("Caustica {} provider {} failed while stopping", kind, entry.getKey(), t);
        }
    }

    private <T> void shutdownStopped(String kind, Map<ProviderId, T> providers, Consumer<T> action) {
        for (Map.Entry<ProviderId, T> entry : providers.entrySet()) {
            ProviderKey key = new ProviderKey(kind, entry.getKey());
            if (!stoppedThisSession.contains(key) || !shutDownThisSession.add(key)) {
                continue;
            }
            try {
                action.accept(entry.getValue());
            } catch (Throwable t) {
                failed.add(key);
                CausticaMod.LOGGER.error("Caustica {} provider {} failed during shutdown", kind, entry.getKey(), t);
            }
        }
    }

    private record ProviderKey(String kind, ProviderId id) {
    }
}
