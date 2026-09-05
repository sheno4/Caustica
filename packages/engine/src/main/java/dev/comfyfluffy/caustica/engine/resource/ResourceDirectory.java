package dev.comfyfluffy.caustica.engine.resource;

import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import dev.comfyfluffy.caustica.support.SharedResource;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Shared resource ownership with final destruction serialized on a dedicated worker. */
public final class ResourceDirectory implements AutoCloseable {
    private final Consumer<Throwable> failures;
    private final Map<ContributionOwner, Boolean> owners = new IdentityHashMap<>();
    private final Set<State> resources = new LinkedHashSet<>();
    private final ExecutorService retirement = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("Caustica resource retirement").factory());
    private boolean closed;

    public ResourceDirectory(Consumer<Throwable> failures) { this.failures = failures; }

    public synchronized ResourceFactory openFactory(ContributionOwner owner) {
        if (closed) throw new IllegalStateException("resource directory is closed");
        owners.putIfAbsent(owner, true);
        return destroy -> create(owner, destroy);
    }

    private synchronized ResourceOwner create(ContributionOwner owner, Runnable destroy) {
        if (closed || !Boolean.TRUE.equals(owners.get(owner))) {
            throw new IllegalStateException("resource factory is closed");
        }
        State state = new State(this, owner, destroy);
        resources.add(state);
        return state.producer;
    }

    /** Resources may be shared across contributions in the same device session. */
    public synchronized void validate(ContributionOwner owner, ResourceRef reference) {
        if (reference == ResourceRef.none()) return;
        if (!(reference instanceof State state) || state.directory != this) {
            throw new IllegalArgumentException("resource belongs to another device session");
        }
        if (!state.lifetime.isAlive()) throw new IllegalStateException("resource has been released");
    }

    public synchronized ResourceOwner acquire(ContributionOwner owner, ResourceRef reference) {
        validate(owner, reference);
        return reference.retain();
    }

    private synchronized void retire(State state) {
        retirement.execute(() -> {
            try {
                state.destroy.run();
            } catch (Throwable failure) {
                failures.accept(failure);
            } finally {
                synchronized (ResourceDirectory.this) {
                    resources.remove(state);
                    ResourceDirectory.this.notifyAll();
                }
            }
        });
    }

    public synchronized void quiesce(ContributionOwner owner) { owners.put(owner, false); }

    /** Release factory-issued producer claims without revoking references held by other consumers. */
    public void invalidate(ContributionOwner owner) {
        java.util.List<Claim> claims;
        synchronized (this) {
            quiesce(owner);
            claims = resources.stream().filter(state -> state.owner == owner)
                    .map(state -> state.producer).toList();
        }
        claims.forEach(Claim::close);
    }

    /** Settle frame uses and eligible destruction without waiting for independent shared owners. */
    public void drain(ContributionOwner owner, Runnable settleFrameUses) {
        settleFrameUses.run();
        awaitRetirements();
    }

    /** Wait for all currently queued destruction work, including callbacks releasing dependencies. */
    public void awaitRetirements() {
        synchronized (this) {
            while (resources.stream().anyMatch(state -> !state.lifetime.isAlive())) awaitChange();
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            awaitRetirements();
            if (resources.stream().anyMatch(state -> state.lifetime.isAlive())) {
                throw new IllegalStateException("resource owners remain live");
            }
            while (!resources.isEmpty()) awaitChange();
            closed = true;
        }
        retirement.shutdown();
    }

    private void awaitChange() {
        try {
            wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retiring GPU resources", interrupted);
        }
    }

    private static final class State implements ResourceRef {
        final ResourceDirectory directory;
        final ContributionOwner owner;
        final Runnable destroy;
        final Claim producer;
        final SharedResource.Reference<State> lifetime;

        State(ResourceDirectory directory, ContributionOwner owner, Runnable destroy) {
            this.directory = directory;
            this.owner = owner;
            this.destroy = destroy;
            SharedResource<State> initial = SharedResource.owned(this, directory::retire);
            lifetime = initial.reference();
            producer = new Claim(initial);
        }

        @Override public ResourceOwner retain() { return new Claim(lifetime.retain()); }
    }

    private static final class Claim implements ResourceOwner {
        private final State state;
        private final SharedResource<State> owner;

        Claim(SharedResource<State> owner) {
            this.owner = owner;
            state = owner.get();
        }
        @Override public ResourceRef reference() { return state; }
        @Override public ResourceOwner retain() { return new Claim(owner.retain()); }
        @Override public void close() { owner.close(); }
    }
}
