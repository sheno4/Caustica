package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProviderManagerTest {
    @Test
    void disablesOnlyTheFailingProvider() {
        AtomicInteger failedCalls = new AtomicInteger();
        AtomicInteger cleanupCalls = new AtomicInteger();
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
            public void shutdown() {
                cleanupCalls.incrementAndGet();
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
        assertEquals(1, cleanupCalls.get());
        assertEquals(2, healthyCalls.get());
    }

    @Test
    void aProviderTornDownByFailureIsNotShutDownAgainAtSessionClose() {
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider failing = counting(shutdowns, () -> {
            throw new IllegalStateException("expected");
        });
        ProviderManager manager = new ProviderManager(Map.of(failing.id(), failing), Map.of(), Map.of());

        manager.updateScenes();
        manager.shutdown();

        assertEquals(1, shutdowns.get());
    }

    @Test
    void shutdownRunsOncePerProviderEvenIfCalledRepeatedly() {
        AtomicInteger shutdowns = new AtomicInteger();
        SceneProvider healthy = counting(shutdowns, () -> {
        });
        ProviderManager manager = new ProviderManager(Map.of(healthy.id(), healthy), Map.of(), Map.of());

        manager.shutdown();
        manager.shutdown();

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
}
