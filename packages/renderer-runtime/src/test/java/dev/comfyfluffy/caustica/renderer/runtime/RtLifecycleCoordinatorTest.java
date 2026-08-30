package dev.comfyfluffy.caustica.renderer.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class RtLifecycleCoordinatorTest {
    @Test
    void initialResourcePackOpensAnIndependentEpoch() {
        Events events = new Events();
        RtLifecycleCoordinator coordinator = new RtLifecycleCoordinator(events);

        coordinator.startProcess();
        assertEquals(1, coordinator.observeResourcePackAvailable().generation());

        assertEquals(List.of("process:start", "pack:applied:1"), events.events);
        assertEquals(1, coordinator.resourcePackEpoch().generation());
    }

    @Test
    void onlyTheLatestReloadCompletionCanPublish() {
        Events events = new Events();
        RtLifecycleCoordinator coordinator = new RtLifecycleCoordinator(events);
        coordinator.startProcess();
        coordinator.observeResourcePackAvailable();

        RtLifecycleCoordinator.ResourcePackEpoch first = coordinator.beginResourcePackReload();
        CompletableFuture<Void> firstFuture = new CompletableFuture<>();
        coordinator.trackResourcePackReload(first.generation(), firstFuture);
        RtLifecycleCoordinator.ResourcePackEpoch second = coordinator.beginResourcePackReload();
        CompletableFuture<Void> secondFuture = new CompletableFuture<>();
        coordinator.trackResourcePackReload(second.generation(), secondFuture);
        firstFuture.complete(null);
        assertEquals(List.of(), coordinator.drainResourcePackCompletions());
        secondFuture.complete(null);

        assertEquals(List.of(second), coordinator.drainResourcePackCompletions());
        assertEquals(second, coordinator.resourcePackEpoch());
        assertEquals(List.of("process:start", "pack:applied:1", "pack:start:2", "pack:applied:3"),
                events.events);
    }

    @Test
    void failedReloadKeepsThePriorResourcePackEpoch() {
        Events events = new Events();
        RtLifecycleCoordinator coordinator = new RtLifecycleCoordinator(events);
        coordinator.startProcess();
        RtLifecycleCoordinator.ResourcePackEpoch initial = coordinator.observeResourcePackAvailable();
        RtLifecycleCoordinator.ResourcePackEpoch pending = coordinator.beginResourcePackReload();
        CompletableFuture<Void> failed = new CompletableFuture<>();
        coordinator.trackResourcePackReload(pending.generation(), failed);
        failed.completeExceptionally(new IllegalStateException("reload failed"));

        assertEquals(List.of(), coordinator.drainResourcePackCompletions());
        assertEquals(initial, coordinator.resourcePackEpoch());
        assertNull(coordinator.pendingResourcePackEpoch());
        assertEquals(List.of("process:start", "pack:applied:1", "pack:start:2", "pack:failed:2"),
                events.events);
    }

    @Test
    void processStopClosesResourcePackThenDeviceBeforeProcess() {
        Events events = new Events();
        RtLifecycleCoordinator coordinator = new RtLifecycleCoordinator(events);
        Object device = new Object();
        coordinator.startProcess();
        coordinator.observeDevice(device);
        coordinator.observeResourcePackAvailable();

        coordinator.stopProcess();

        assertEquals(List.of("process:start", "device:observed:1", "pack:applied:1",
                "pack:closing:1", "device:closing:1", "process:stop"), events.events);
        assertNull(coordinator.deviceEpoch());
    }

    @Test
    void renderSessionsAreIndependentFromTheDeviceAndCloseBeforeProcessStop() {
        Events events = new Events();
        RtLifecycleCoordinator coordinator = new RtLifecycleCoordinator(events);
        coordinator.startProcess();
        RtLifecycleCoordinator.RenderSessionEpoch first = coordinator.beginRenderSession();
        RtLifecycleCoordinator.RuntimeActivationEpoch activation = coordinator.beginRuntimeActivation();
        coordinator.closeRuntimeActivation(activation);
        RtLifecycleCoordinator.RuntimeActivationEpoch replacement = coordinator.beginRuntimeActivation();
        coordinator.closeRenderSession(first);
        RtLifecycleCoordinator.RenderSessionEpoch second = coordinator.beginRenderSession();
        coordinator.beginRuntimeActivation();

        coordinator.stopProcess();

        assertEquals(List.of("process:start", "session:start:1", "activation:start:1",
                "activation:closing:1", "activation:start:2", "activation:closing:2",
                "session:closing:1", "session:start:2", "activation:start:3",
                "activation:closing:3", "session:closing:2", "process:stop"), events.events);
        assertNull(coordinator.renderSessionEpoch());
        assertNull(coordinator.runtimeActivationEpoch());
    }

    private static final class Events implements RtLifecycleCoordinator.Listener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void processStarted() {
            events.add("process:start");
        }

        @Override
        public void processStopping() {
            events.add("process:stop");
        }

        @Override
        public void deviceObserved(RtLifecycleCoordinator.DeviceEpoch epoch) {
            events.add("device:observed:" + epoch.generation());
        }

        @Override
        public void deviceClosing(RtLifecycleCoordinator.DeviceEpoch epoch) {
            events.add("device:closing:" + epoch.generation());
        }

        @Override
        public void renderSessionStarted(RtLifecycleCoordinator.RenderSessionEpoch epoch) {
            events.add("session:start:" + epoch.generation());
        }

        @Override
        public void renderSessionClosing(RtLifecycleCoordinator.RenderSessionEpoch epoch) {
            events.add("session:closing:" + epoch.generation());
        }

        @Override
        public void runtimeActivationStarted(RtLifecycleCoordinator.RuntimeActivationEpoch epoch) {
            events.add("activation:start:" + epoch.generation());
        }

        @Override
        public void runtimeActivationClosing(RtLifecycleCoordinator.RuntimeActivationEpoch epoch) {
            events.add("activation:closing:" + epoch.generation());
        }

        @Override
        public void resourcePackReloadStarting(RtLifecycleCoordinator.ResourcePackEpoch epoch) {
            events.add("pack:start:" + epoch.generation());
        }

        @Override
        public void resourcePackClosing(RtLifecycleCoordinator.ResourcePackEpoch epoch) {
            events.add("pack:closing:" + epoch.generation());
        }

        @Override
        public void resourcePackApplied(RtLifecycleCoordinator.ResourcePackEpoch epoch) {
            events.add("pack:applied:" + epoch.generation());
        }

        @Override
        public void resourcePackReloadFailed(RtLifecycleCoordinator.ResourcePackEpoch epoch, Throwable failure) {
            events.add("pack:failed:" + epoch.generation());
        }

    }
}
