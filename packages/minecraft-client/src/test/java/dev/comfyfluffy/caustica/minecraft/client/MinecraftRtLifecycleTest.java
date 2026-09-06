package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MinecraftRtLifecycleTest {
    @Test
    void initialResourcePackOpensAnIndependentEpoch() {
        Events events = new Events();
        MinecraftRtLifecycle coordinator = new MinecraftRtLifecycle(events);

        assertEquals(1, coordinator.observeResourcePackAvailable().generation());

        assertEquals(List.of("pack:applied:1"), events.events);
        assertEquals(1, coordinator.resourcePackEpoch().generation());
    }

    @Test
    void onlyTheLatestReloadCompletionCanPublish() {
        Events events = new Events();
        MinecraftRtLifecycle coordinator = new MinecraftRtLifecycle(events);
        coordinator.observeResourcePackAvailable();

        ResourcePackEpoch first = coordinator.beginResourcePackReload();
        CompletableFuture<Void> firstFuture = new CompletableFuture<>();
        coordinator.trackResourcePackReload(first.generation(), firstFuture);
        ResourcePackEpoch second = coordinator.beginResourcePackReload();
        CompletableFuture<Void> secondFuture = new CompletableFuture<>();
        coordinator.trackResourcePackReload(second.generation(), secondFuture);
        firstFuture.complete(null);
        coordinator.drainResourcePackCompletions();
        assertEquals(1, coordinator.resourcePackEpoch().generation());
        secondFuture.complete(null);

        coordinator.drainResourcePackCompletions();
        assertEquals(second, coordinator.resourcePackEpoch());
        assertEquals(List.of("pack:applied:1", "pack:start:2", "pack:applied:3"),
                events.events);
    }

    @Test
    void failedReloadKeepsThePriorResourcePackEpoch() {
        Events events = new Events();
        MinecraftRtLifecycle coordinator = new MinecraftRtLifecycle(events);
        ResourcePackEpoch initial = coordinator.observeResourcePackAvailable();
        ResourcePackEpoch pending = coordinator.beginResourcePackReload();
        CompletableFuture<Void> failed = new CompletableFuture<>();
        coordinator.trackResourcePackReload(pending.generation(), failed);
        failed.completeExceptionally(new IllegalStateException("reload failed"));

        coordinator.drainResourcePackCompletions();
        assertEquals(initial, coordinator.resourcePackEpoch());
        assertNull(coordinator.pendingResourcePackEpoch());
        assertEquals(List.of("pack:applied:1", "pack:start:2", "pack:failed:2"),
                events.events);
    }

    @Test
    void workerCompletionWaitsForClientDrain() {
        Events events = new Events();
        MinecraftRtLifecycle coordinator = new MinecraftRtLifecycle(events);
        ResourcePackEpoch pending = coordinator.beginResourcePackReload();
        CompletableFuture<Void> future = new CompletableFuture<>();
        coordinator.trackResourcePackReload(pending.generation(), future);

        CompletableFuture.runAsync(() -> future.complete(null)).join();

        assertNull(coordinator.resourcePackEpoch());
        assertEquals(List.of("pack:start:1"), events.events);
        coordinator.drainResourcePackCompletions();
        assertEquals(pending, coordinator.resourcePackEpoch());
        assertEquals(List.of("pack:start:1", "pack:applied:1"), events.events);
    }

    @Test
    void shutdownDiscardsQueuedAndLateReloadCompletions() {
        Events events = new Events();
        MinecraftRtLifecycle coordinator = new MinecraftRtLifecycle(events);
        ResourcePackEpoch first = coordinator.beginResourcePackReload();
        coordinator.trackResourcePackReload(first.generation(), CompletableFuture.completedFuture(null));
        ResourcePackEpoch second = coordinator.beginResourcePackReload();
        CompletableFuture<Void> late = new CompletableFuture<>();
        coordinator.trackResourcePackReload(second.generation(), late);

        coordinator.clear();
        assertNull(coordinator.resourcePackEpoch());
        assertNull(coordinator.pendingResourcePackEpoch());
        ResourcePackEpoch replacement = coordinator.beginResourcePackReload();
        late.complete(null);
        coordinator.drainResourcePackCompletions();

        assertNull(coordinator.resourcePackEpoch());
        assertEquals(replacement, coordinator.pendingResourcePackEpoch());
        assertEquals(List.of("pack:start:1", "pack:start:3"), events.events);
    }

    private static final class Events implements MinecraftRtLifecycle.Listener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void resourcePackReloadStarting(ResourcePackEpoch epoch) {
            events.add("pack:start:" + epoch.generation());
        }

        @Override
        public void resourcePackApplied(ResourcePackEpoch epoch) {
            events.add("pack:applied:" + epoch.generation());
        }

        @Override
        public void resourcePackReloadFailed(ResourcePackEpoch epoch, Throwable failure) {
            events.add("pack:failed:" + epoch.generation());
        }

    }
}
