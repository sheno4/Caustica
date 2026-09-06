package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tracks client resource-pack reloads. Worker completions are queued for the client thread so
 * listeners only detach or publish renderer resources there.
 */
final class MinecraftRtLifecycle {
    interface Listener {
        void resourcePackReloadStarting(ResourcePackEpoch pending);
        void resourcePackApplied(ResourcePackEpoch epoch);
        void resourcePackReloadFailed(ResourcePackEpoch pending, Throwable failure);
    }

    private record ResourcePackCompletion(long generation, Throwable failure) {}

    private final Listener listener;
    private final ConcurrentLinkedQueue<ResourcePackCompletion> resourcePackCompletions =
            new ConcurrentLinkedQueue<>();
    private ResourcePackEpoch resourcePackEpoch;
    private ResourcePackEpoch pendingResourcePackEpoch;
    private long nextResourcePackGeneration;

    MinecraftRtLifecycle(Listener listener) {
        this.listener = listener;
    }

    /**
     * Mark the initial resource pack available, or begin the next resource-pack epoch when no reload is
     * already in flight. This is intentionally idempotent for the ordinary client tick path.
     */
    synchronized ResourcePackEpoch observeResourcePackAvailable() {
        if (resourcePackEpoch != null || pendingResourcePackEpoch != null) {
            return resourcePackEpoch;
        }
        ResourcePackEpoch epoch = new ResourcePackEpoch(++nextResourcePackGeneration);
        resourcePackEpoch = epoch;
        listener.resourcePackApplied(epoch);
        return epoch;
    }

    /** Start the detach phase before the host destroys images from the current resource pack. */
    synchronized ResourcePackEpoch beginResourcePackReload() {
        boolean detachActivePack = pendingResourcePackEpoch == null;
        ResourcePackEpoch pending = new ResourcePackEpoch(++nextResourcePackGeneration);
        pendingResourcePackEpoch = pending;
        if (detachActivePack) {
            listener.resourcePackReloadStarting(pending);
        }
        return pending;
    }

    /** Queue a reload result; callers may invoke this from any thread. */
    void trackResourcePackReload(long generation, CompletableFuture<?> future) {
        future.whenComplete((ignored, failure) -> resourcePackCompletions.add(
                new ResourcePackCompletion(generation, failure)));
    }

    /** Apply queued reload completions on the client thread. */
    void drainResourcePackCompletions() {
        for (ResourcePackCompletion completion; (completion = resourcePackCompletions.poll()) != null;) {
            completeResourcePackReload(completion.generation(), completion.failure());
        }
    }

    /**
     * Accept a resource-pack reload result. A result for a superseded request is ignored, which prevents an
     * older asynchronous reload from publishing after a newer one began.
     */
    private synchronized void completeResourcePackReload(long generation, Throwable failure) {
        ResourcePackEpoch pending = pendingResourcePackEpoch;
        if (pending == null || pending.generation() != generation) {
            return;
        }
        pendingResourcePackEpoch = null;
        if (failure != null) {
            listener.resourcePackReloadFailed(pending, failure);
            return;
        }
        resourcePackEpoch = pending;
        listener.resourcePackApplied(pending);
    }

    /** Discard pack state at shutdown; late completions cannot match a future reload generation. */
    synchronized void clear() {
        pendingResourcePackEpoch = null;
        resourcePackEpoch = null;
        resourcePackCompletions.clear();
    }

    synchronized ResourcePackEpoch resourcePackEpoch() {
        return resourcePackEpoch;
    }

    synchronized ResourcePackEpoch pendingResourcePackEpoch() {
        return pendingResourcePackEpoch;
    }
}
