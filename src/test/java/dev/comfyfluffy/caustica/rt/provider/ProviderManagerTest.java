package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProviderManagerTest {
    @Test
    void disablesOnlyTheFailingProvider() {
        AtomicInteger failedCalls = new AtomicInteger();
        AtomicInteger stopCalls = new AtomicInteger();
        AtomicInteger shutdownCalls = new AtomicInteger();
        AtomicInteger healthyCalls = new AtomicInteger();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void update() {
                failedCalls.incrementAndGet();
                throw new IllegalStateException("expected");
            }

            @Override
            public void stop() {
                stopCalls.incrementAndGet();
            }

            @Override
            public void shutdown() {
                shutdownCalls.incrementAndGet();
            }
        };
        SceneProvider healthy = new SceneProvider() {
            @Override
            public void update() {
                healthyCalls.incrementAndGet();
            }
        };
        Map<ProviderId, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(id("failing"), failing);
        scenes.put(id("healthy"), healthy);
        ProviderManager manager = new ProviderManager(scenes, Map.of(), Map.of());

        manager.updateScenes();
        manager.updateScenes();

        assertEquals(1, failedCalls.get());
        assertEquals(1, stopCalls.get());
        assertEquals(0, shutdownCalls.get());
        assertEquals(2, healthyCalls.get());

        shutdown(manager);
        assertEquals(1, shutdownCalls.get());
    }

    @Test
    void aProviderTornDownByFailureIsNotShutDownAgainAtSessionClose() {
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider failing = counting(shutdowns, () -> {
            throw new IllegalStateException("expected");
        });
        ProviderManager manager = manager("failing", failing);

        manager.updateScenes();
        shutdown(manager);

        assertEquals(1, shutdowns.get());
    }

    @Test
    void shutdownRunsOncePerProviderEvenIfCalledRepeatedly() {
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider healthy = counting(shutdowns, () -> {
        });
        ProviderManager manager = manager("healthy", healthy);

        shutdown(manager);
        shutdown(manager);

        assertEquals(1, shutdowns.get());
    }

    @Test
    void stopAndShutdownAreSeparateOrderedPhases() {
        List<String> events = new ArrayList<>();
        SceneProvider provider = new SceneProvider() {
            @Override
            public void stop() {
                events.add("stop");
            }

            @Override
            public void shutdown() {
                events.add("shutdown");
            }
        };
        ProviderManager manager = manager("phased", provider);

        manager.stopProviders();
        assertEquals(List.of("stop"), events);

        manager.shutdownResources();
        assertEquals(List.of("stop", "shutdown"), events);
    }

    @Test
    void aNormallyStoppedProviderRunsAgainInTheNextSession() {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider healthy = counting(shutdowns, updates::incrementAndGet);
        ProviderManager manager = manager("healthy", healthy);

        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);
        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);

        assertEquals(2, updates.get());
        assertEquals(2, shutdowns.get());
    }

    @Test
    void aFailedProviderRemainsDisabledAcrossSessions() {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider failing = counting(shutdowns, () -> {
            updates.incrementAndGet();
            throw new IllegalStateException("expected");
        });
        ProviderManager manager = manager("failing", failing);

        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);
        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);

        assertEquals(1, updates.get());
        assertEquals(1, shutdowns.get());
    }

    @Test
    void aProviderWhoseShutdownFailsRemainsDisabledAcrossSessions() {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void update() {
                updates.incrementAndGet();
            }

            @Override
            public void shutdown() {
                shutdowns.incrementAndGet();
                throw new IllegalStateException("expected");
            }
        };
        ProviderManager manager = manager("shutdown_failure", failing);

        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);
        manager.beginSession();
        manager.updateScenes();
        shutdown(manager);

        assertEquals(1, updates.get());
        assertEquals(1, shutdowns.get());
    }

    @Test
    void aTornDownProviderNeverReceivesAnotherCallback() {
        AtomicInteger updates = new AtomicInteger();
        SceneProvider failing = new SceneProvider() {
            @Override
            public void update() {
                updates.incrementAndGet();
                throw new IllegalStateException("expected");
            }
        };
        ProviderManager manager = manager("failing", failing);

        manager.updateScenes();
        manager.prepareFrame();
        manager.invalidateScenes();
        manager.updateScenes();

        assertEquals(1, updates.get());
    }

    private static SceneProvider counting(AtomicInteger shutdowns, Runnable update) {
        return new SceneProvider() {
            @Override
            public void update() {
                update.run();
            }

            @Override
            public void shutdown() {
                shutdowns.incrementAndGet();
            }
        };
    }

    private static void shutdown(ProviderManager manager) {
        manager.stopProviders();
        manager.shutdownResources();
    }

    private static ProviderManager manager(String path, SceneProvider provider) {
        return new ProviderManager(Map.of(id(path), provider), Map.of(), Map.of());
    }

    private static ProviderId id(String path) {
        return ProviderId.of("test", path);
    }
}
