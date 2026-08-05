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
}
