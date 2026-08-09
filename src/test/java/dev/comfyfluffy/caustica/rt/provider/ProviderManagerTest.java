package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import net.minecraft.resources.Identifier;
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
            public Identifier id() {
                return Identifier.fromNamespaceAndPath("test", "failing");
            }

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
            public Identifier id() {
                return Identifier.fromNamespaceAndPath("test", "healthy");
            }

            @Override
            public void update() {
                healthyCalls.incrementAndGet();
            }
        };
        Map<Identifier, SceneProvider> scenes = new LinkedHashMap<>();
        scenes.put(failing.id(), failing);
        scenes.put(healthy.id(), healthy);
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
        ProviderManager manager = new ProviderManager(Map.of(failing.id(), failing), Map.of(), Map.of());

        manager.updateScenes();
        shutdown(manager);

        assertEquals(1, shutdowns.get());
    }

    @Test
    void shutdownRunsOncePerProviderEvenIfCalledRepeatedly() {
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider healthy = counting(shutdowns, () -> {
        });
        ProviderManager manager = new ProviderManager(Map.of(healthy.id(), healthy), Map.of(), Map.of());

        shutdown(manager);
        shutdown(manager);

        assertEquals(1, shutdowns.get());
    }

    @Test
    void stopAndShutdownAreSeparateOrderedPhases() {
        List<String> events = new ArrayList<>();
        SceneProvider provider = new SceneProvider() {
            @Override
            public Identifier id() {
                return Identifier.fromNamespaceAndPath("test", "phased");
            }

            @Override
            public void stop() {
                events.add("stop");
            }

            @Override
            public void shutdown() {
                events.add("shutdown");
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(provider.id(), provider), Map.of(), Map.of());

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
        ProviderManager manager = new ProviderManager(Map.of(healthy.id(), healthy), Map.of(), Map.of());

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
        ProviderManager manager = new ProviderManager(Map.of(failing.id(), failing), Map.of(), Map.of());

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
            public Identifier id() {
                return Identifier.fromNamespaceAndPath("test", "shutdown_failure");
            }

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
        ProviderManager manager = new ProviderManager(Map.of(failing.id(), failing), Map.of(), Map.of());

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
            public Identifier id() {
                return Identifier.fromNamespaceAndPath("test", "failing");
            }

            @Override
            public void update() {
                updates.incrementAndGet();
                throw new IllegalStateException("expected");
            }
        };
        ProviderManager manager = new ProviderManager(Map.of(failing.id(), failing), Map.of(), Map.of());

        manager.updateScenes();
        manager.prepareFrame();
        manager.invalidateScenes();
        manager.updateScenes();

        assertEquals(1, updates.get());
    }

    private static SceneProvider counting(AtomicInteger shutdowns, Runnable update) {
        return new SceneProvider() {
            @Override
            public Identifier id() {
                return Identifier.fromNamespaceAndPath("test", "counting");
            }

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
}
