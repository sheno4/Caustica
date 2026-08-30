package dev.comfyfluffy.caustica.renderer.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Serializes host lifecycle facts into process, device, render-session, runtime-activation, and
 * resource-pack epochs.
 *
 * <p>Host callbacks may start a resource-pack reload on the client thread and complete its future on a
 * loader worker. Completion is queued here and applied only by {@link #drainResourcePackCompletions()} on
 * the client thread, so epoch listeners never receive Vulkan-facing callbacks from a loader worker.</p>
 */
public final class RtLifecycleCoordinator {
    public interface Listener {
        default void processStarted() {
        }

        default void processStopping() {
        }

        default void deviceObserved(DeviceEpoch epoch) {
        }

        default void deviceClosing(DeviceEpoch epoch) {
        }

        default void renderSessionStarted(RenderSessionEpoch epoch) {
        }

        default void renderSessionClosing(RenderSessionEpoch epoch) {
        }

        default void runtimeActivationStarted(RuntimeActivationEpoch epoch) {
        }

        default void runtimeActivationClosing(RuntimeActivationEpoch epoch) {
        }

        default void resourcePackReloadStarting(ResourcePackEpoch pending) {
        }

        default void resourcePackClosing(ResourcePackEpoch epoch) {
        }

        default void resourcePackApplied(ResourcePackEpoch epoch) {
        }

        default void resourcePackReloadFailed(ResourcePackEpoch pending, Throwable failure) {
        }

    }

    public record DeviceEpoch(long generation) {
    }

    /** A requested RT runtime, independent from program compilation and device ownership. */
    public record RenderSessionEpoch(long generation) {
    }

    /** The selected runtime closure inside one requested RT session. */
    public record RuntimeActivationEpoch(long generation) {
    }

    public record ResourcePackEpoch(long generation) {
    }

    private record ResourcePackCompletion(long generation, Throwable failure) {
    }

    private final Listener listener;
    private final ConcurrentLinkedQueue<ResourcePackCompletion> resourcePackCompletions =
            new ConcurrentLinkedQueue<>();

    private boolean processStarted;
    private Object deviceIdentity;
    private DeviceEpoch deviceEpoch;
    private long nextDeviceGeneration;
    private RenderSessionEpoch renderSessionEpoch;
    private long nextRenderSessionGeneration;
    private RuntimeActivationEpoch runtimeActivationEpoch;
    private long nextRuntimeActivationGeneration;
    private ResourcePackEpoch resourcePackEpoch;
    private ResourcePackEpoch pendingResourcePackEpoch;
    private long nextResourcePackGeneration;

    public RtLifecycleCoordinator(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Open the process lifetime after extension registration and option loading have completed. */
    public synchronized void startProcess() {
        if (processStarted) {
            return;
        }
        processStarted = true;
        listener.processStarted();
    }

    /** Observe the current Vulkan device without creating any renderer resources. */
    public synchronized void observeDevice(Object identity) {
        requireStarted();
        Objects.requireNonNull(identity, "identity");
        if (identity == deviceIdentity) {
            return;
        }
        if (deviceEpoch != null) {
            throw new IllegalStateException("Cannot replace an observed Vulkan device before its lifecycle closes");
        }
        deviceIdentity = identity;
        deviceEpoch = new DeviceEpoch(++nextDeviceGeneration);
        listener.deviceObserved(deviceEpoch);
    }

    /** Close the currently observed device after all child lifetimes have retired. */
    public synchronized void closeDevice(Object identity) {
        if (identity != deviceIdentity) {
            return;
        }
        if (deviceEpoch != null) {
            listener.deviceClosing(deviceEpoch);
        }
        deviceIdentity = null;
        deviceEpoch = null;
    }

    /** Open the RT runtime lifetime after RT startup has been requested. */
    public synchronized RenderSessionEpoch beginRenderSession() {
        requireStarted();
        if (renderSessionEpoch != null) {
            throw new IllegalStateException("An RT render session is already active");
        }
        renderSessionEpoch = new RenderSessionEpoch(++nextRenderSessionGeneration);
        listener.renderSessionStarted(renderSessionEpoch);
        return renderSessionEpoch;
    }

    /** Close the RT runtime lifetime before its scoped instances release their GPU resources. */
    public synchronized void closeRenderSession(RenderSessionEpoch epoch) {
        if (epoch == null || epoch != renderSessionEpoch) {
            return;
        }
        closeRuntimeActivation(runtimeActivationEpoch);
        listener.renderSessionClosing(epoch);
        renderSessionEpoch = null;
    }

    /** Open the child lifetime that owns factory-created runtime contributions. */
    public synchronized RuntimeActivationEpoch beginRuntimeActivation() {
        requireStarted();
        if (renderSessionEpoch == null) {
            throw new IllegalStateException("A runtime activation requires an RT render session");
        }
        if (runtimeActivationEpoch != null) {
            throw new IllegalStateException("A runtime activation is already active");
        }
        runtimeActivationEpoch = new RuntimeActivationEpoch(++nextRuntimeActivationGeneration);
        listener.runtimeActivationStarted(runtimeActivationEpoch);
        return runtimeActivationEpoch;
    }

    /** Close the current runtime closure while preserving its parent RT session. */
    public synchronized void closeRuntimeActivation(RuntimeActivationEpoch epoch) {
        if (epoch == null || epoch != runtimeActivationEpoch) {
            return;
        }
        listener.runtimeActivationClosing(epoch);
        runtimeActivationEpoch = null;
    }

    /**
     * Mark the initial resource pack available, or begin the next resource-pack epoch when no reload is
     * already in flight. This is intentionally idempotent for the ordinary client tick path.
     */
    public synchronized ResourcePackEpoch observeResourcePackAvailable() {
        requireStarted();
        if (resourcePackEpoch != null || pendingResourcePackEpoch != null) {
            return resourcePackEpoch;
        }
        ResourcePackEpoch epoch = new ResourcePackEpoch(++nextResourcePackGeneration);
        resourcePackEpoch = epoch;
        listener.resourcePackApplied(epoch);
        return epoch;
    }

    /** Start the detach phase before the host destroys images from the current resource pack. */
    public synchronized ResourcePackEpoch beginResourcePackReload() {
        requireStarted();
        boolean detachActivePack = pendingResourcePackEpoch == null;
        ResourcePackEpoch pending = new ResourcePackEpoch(++nextResourcePackGeneration);
        pendingResourcePackEpoch = pending;
        if (detachActivePack) {
            listener.resourcePackReloadStarting(pending);
        }
        return pending;
    }

    /** Queue a reload result; callers may invoke this from any thread. */
    public void trackResourcePackReload(long generation, CompletableFuture<?> future) {
        Objects.requireNonNull(future, "future");
        future.whenComplete((ignored, failure) -> resourcePackCompletions.add(
                new ResourcePackCompletion(generation, failure)));
    }

    /** Apply queued reload completions on the client thread, returning the accepted transitions. */
    public List<ResourcePackEpoch> drainResourcePackCompletions() {
        List<ResourcePackEpoch> applied = new ArrayList<>();
        for (ResourcePackCompletion completion; (completion = resourcePackCompletions.poll()) != null;) {
            ResourcePackEpoch accepted = completeResourcePackReload(completion.generation(), completion.failure());
            if (accepted != null) {
                applied.add(accepted);
            }
        }
        return List.copyOf(applied);
    }

    /**
     * Accept a resource-pack reload result. A result for a superseded request is ignored, which prevents an
     * older asynchronous reload from publishing after a newer one began.
     */
    public synchronized ResourcePackEpoch completeResourcePackReload(long generation, Throwable failure) {
        ResourcePackEpoch pending = pendingResourcePackEpoch;
        if (pending == null || pending.generation() != generation) {
            return null;
        }
        pendingResourcePackEpoch = null;
        if (failure != null) {
            listener.resourcePackReloadFailed(pending, failure);
            return null;
        }
        resourcePackEpoch = pending;
        listener.resourcePackApplied(pending);
        return pending;
    }

    /** Close child epochs before the process runtime is destroyed. */
    public synchronized void stopProcess() {
        if (!processStarted) {
            return;
        }
        closeRenderSession(renderSessionEpoch);
        pendingResourcePackEpoch = null;
        resourcePackCompletions.clear();
        if (resourcePackEpoch != null) {
            listener.resourcePackClosing(resourcePackEpoch);
        }
        resourcePackEpoch = null;
        if (deviceEpoch != null) {
            listener.deviceClosing(deviceEpoch);
        }
        deviceIdentity = null;
        deviceEpoch = null;
        processStarted = false;
        listener.processStopping();
    }

    public synchronized boolean processStarted() {
        return processStarted;
    }

    public synchronized DeviceEpoch deviceEpoch() {
        return deviceEpoch;
    }

    public synchronized RenderSessionEpoch renderSessionEpoch() {
        return renderSessionEpoch;
    }

    public synchronized RuntimeActivationEpoch runtimeActivationEpoch() {
        return runtimeActivationEpoch;
    }

    public synchronized ResourcePackEpoch resourcePackEpoch() {
        return resourcePackEpoch;
    }

    public synchronized ResourcePackEpoch pendingResourcePackEpoch() {
        return pendingResourcePackEpoch;
    }

    private void requireStarted() {
        if (!processStarted) {
            throw new IllegalStateException("Caustica process lifecycle has not started");
        }
    }
}
