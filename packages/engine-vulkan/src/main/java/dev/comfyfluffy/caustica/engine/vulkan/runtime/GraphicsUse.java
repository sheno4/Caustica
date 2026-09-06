package dev.comfyfluffy.caustica.engine.vulkan.runtime;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Completion reservation for one graphics frame on an {@link GraphicsQueue} timeline. */
public final class GraphicsUse implements GpuFrameUse {
    private final GraphicsQueue owner;
    private final long value;
    private final ArrayList<Runnable> submittedCallbacks = new ArrayList<>();
    private final ArrayList<Runnable> resolvedCallbacks = new ArrayList<>();
    private final ArrayList<AutoCloseable> keepAlives = new ArrayList<>();
    private boolean commandsAccepted;
    private boolean submittedResolved;

    GraphicsUse(GraphicsQueue owner, long value) {
        this.owner = owner;
        this.value = value;
    }

    GraphicsQueue owner() {
        return owner;
    }

    long value() {
        return value;
    }

    @Override
    public void whenSubmitted(Runnable callback) {
        if (submittedResolved) throw new IllegalStateException("graphics submission callbacks are resolved");
        submittedCallbacks.add(Objects.requireNonNull(callback, "callback"));
    }

    /**
     * Holds a lease while this frame is recording. Accepted commands transfer it to the graphics
     * timeline; an abandoned frame releases it during submission resolution because the GPU never
     * received commands that could read the leased resource.
     */
    public void keepAlive(AutoCloseable lease) {
        if (submittedResolved) throw new IllegalStateException("graphics submission callbacks are resolved");
        keepAlives.add(Objects.requireNonNull(lease, "lease"));
    }

    void whenResolved(Runnable callback) {
        if (submittedResolved) throw new IllegalStateException("graphics use is resolved");
        resolvedCallbacks.add(callback);
    }

    public void commandsAccepted() {
        if (submittedResolved) throw new IllegalStateException("graphics commands are already resolved");
        commandsAccepted = true;
    }

    void resolveSubmission() {
        resolveSubmission(() -> { });
    }

    void resolveSubmission(Runnable signalAcceptedCommands) {
        Consumer<Runnable> retire = owner == null
                ? Runnable::run
                : release -> owner.keepAliveAfterGraphicsValue(value, release);
        resolveSubmission(signalAcceptedCommands, retire);
    }

    void resolveSubmission(Runnable signalAcceptedCommands, Consumer<Runnable> retireAcceptedKeepAlive) {
        Objects.requireNonNull(signalAcceptedCommands, "signalAcceptedCommands");
        Objects.requireNonNull(retireAcceptedKeepAlive, "retireAcceptedKeepAlive");
        if (submittedResolved) {
            throw new IllegalStateException("graphics submission callbacks are resolved");
        }
        boolean signal = commandsAccepted;
        Throwable failure = null;
        try {
            if (signal) fireSubmittedCallbacks();
            else discardSubmittedCallbacks();
        } catch (Throwable callbackFailure) {
            failure = callbackFailure;
        }
        for (Runnable callback : resolvedCallbacks) {
            try { callback.run(); }
            catch (Throwable callbackFailure) {
                if (failure == null) failure = callbackFailure;
                else failure.addSuppressed(callbackFailure);
            }
        }
        resolvedCallbacks.clear();
        Runnable releaseKeepAlives = takeKeepAliveRelease();
        if (releaseKeepAlives != null) {
            try {
                if (signal) retireAcceptedKeepAlive.accept(releaseKeepAlives);
                else if (owner != null) owner.releaseAbandoned(releaseKeepAlives);
                else releaseKeepAlives.run();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
        }
        if (signal) {
            try {
                signalAcceptedCommands.run();
            } catch (Throwable signalFailure) {
                if (failure == null) failure = signalFailure;
                else failure.addSuppressed(signalFailure);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("graphics-use resolution failed", failure);
    }

    private Runnable takeKeepAliveRelease() {
        if (keepAlives.isEmpty()) return null;
        List<AutoCloseable> releases = List.copyOf(keepAlives);
        keepAlives.clear();
        return () -> {
            Throwable failure = null;
            for (AutoCloseable release : releases) {
                try {
                    release.close();
                } catch (Throwable releaseFailure) {
                    if (failure == null) failure = releaseFailure;
                    else failure.addSuppressed(releaseFailure);
                }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("graphics keep-alive release failed", failure);
        };
    }

    private void fireSubmittedCallbacks() {
        if (!commandsAccepted || submittedResolved) {
            throw new IllegalStateException("graphics commands were not accepted exactly once");
        }
        submittedResolved = true;
        runSubmittedCallbacks();
    }

    private void discardSubmittedCallbacks() {
        if (commandsAccepted || submittedResolved) {
            throw new IllegalStateException("graphics commands are already resolved");
        }
        submittedResolved = true;
        submittedCallbacks.clear();
    }

    private void runSubmittedCallbacks() {
        Throwable failure = null;
        for (Runnable callback : submittedCallbacks) {
            try {
                callback.run();
            } catch (Throwable callbackFailure) {
                if (failure == null) failure = callbackFailure;
                else failure.addSuppressed(callbackFailure);
            }
        }
        submittedCallbacks.clear();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("graphics submission callback failed", failure);
    }

    @Override
    public void whenComplete(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        keepAlive(callback::run);
    }
}
