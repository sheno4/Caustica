package dev.comfyfluffy.caustica.engine.resource;

import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/** Session-wide immutable resource generations and serialized retirement delivery. */
public final class ResourceDirectory implements AutoCloseable {
    private final Consumer<Throwable> failures;
    private final Set<ContributionOwner> owners = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ContributionOwner> acceptingOwners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ResourceState> resources = new LinkedHashSet<>();
    private final Queue<ResourceState> callbacks = new ArrayDeque<>();
    private long nextIdentity;
    private boolean closed;

    public ResourceDirectory(Consumer<Throwable> failures) {
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    public synchronized ResourceFactory openFactory(ContributionOwner owner) {
        if (closed) throw new IllegalStateException("resource directory is closed");
        owner = Objects.requireNonNull(owner, "owner");
        if (owners.add(owner)) acceptingOwners.add(owner);
        ContributionOwner boundOwner = owner;
        return retired -> create(boundOwner, retired);
    }

    private synchronized ResourceGeneration create(ContributionOwner owner, Runnable retired) {
        if (closed) throw new IllegalStateException("resource directory is closed");
        requireOwner(owner);
        if (!acceptingOwners.contains(owner)) {
            throw new IllegalStateException("resource creation is quiesced");
        }
        ResourceState state = new ResourceState(
                this, owner, ++nextIdentity, Objects.requireNonNull(retired, "retired"));
        resources.add(state);
        return new Generation(state);
    }

    /** Acquire one renderer borrow, rejecting unsealed, dropped, foreign-session, and foreign-owner roots. */
    public synchronized ResourceLease acquire(ContributionOwner owner, ResourceRef reference) {
        validate(owner, reference);
        return tryAcquire(reference).orElseThrow(
                () -> new IllegalStateException("resource has been dropped"));
    }

    /** Validate a submitted reference against its contribution before accepting the containing mutation. */
    public synchronized void validate(ContributionOwner owner, ResourceRef reference) {
        Objects.requireNonNull(owner, "owner");
        if (reference == ResourceRef.none()) return;
        ResourceState state = requireReference(reference);
        if (state.owner != owner) {
            throw new IllegalArgumentException("resource belongs to another contribution");
        }
        if (state.phase != Phase.SEALED) {
            throw new IllegalStateException("resource is not sealed or has been dropped");
        }
    }

    /**
     * Acquire a trusted, previously validated reference while latching a later snapshot.
     *
     * <p>A dropped generation is unavailable to a new snapshot, while existing leases remain retainable.
     * Foreign-session and never-sealed references are programming errors.
     */
    public synchronized Optional<ResourceLease> tryAcquire(ResourceRef reference) {
        if (reference == ResourceRef.none()) return Optional.of(ResourceLease.none());
        ResourceState state = requireReference(reference);
        if (state.phase == Phase.DROPPED || state.phase == Phase.RETIRED) return Optional.empty();
        if (state.phase != Phase.SEALED) throw new IllegalStateException("resource is not sealed");
        state.references++;
        return Optional.of(new ResourceLease(state));
    }

    static Optional<ResourceLease> tryAcquireTrusted(ResourceRef reference) {
        if (reference == ResourceRef.none()) return Optional.of(ResourceLease.none());
        if (!(reference instanceof Reference concrete)) {
            throw new IllegalArgumentException("resource was not issued by the render session");
        }
        return concrete.state.directory.tryAcquire(reference);
    }

    synchronized ResourceLease retain(ResourceState state) {
        requireState(state);
        if (state.phase == Phase.RETIRED) throw new IllegalStateException("resource is retired");
        state.references++;
        return new ResourceLease(state);
    }

    synchronized void release(ResourceState state) {
        requireState(state);
        if (state.references <= 0) throw new IllegalStateException("resource reference count underflow");
        state.references--;
        scheduleIfRetired(state);
        notifyAll();
    }

    public synchronized void quiesce(ContributionOwner owner) {
        requireOwner(owner);
        acceptingOwners.remove(owner);
    }

    public synchronized void invalidate(ContributionOwner owner) {
        quiesce(owner);
        resources.stream().filter(state -> state.owner == owner).toList().forEach(this::drop);
    }

    public void drain(ContributionOwner owner) {
        drain(owner, () -> { });
    }

    /** Drains an owner, settling external frame leases once if ordinary progress cannot retire it. */
    public void drain(ContributionOwner owner, Runnable settleFrameUses) {
        requireOwner(owner);
        Objects.requireNonNull(settleFrameUses, "settleFrameUses");
        boolean settled = false;
        while (true) {
            progress();
            synchronized (this) {
                if (resources.stream().noneMatch(state -> state.owner == owner)) return;
                if (!callbacks.isEmpty()) continue;
                if (!settled) {
                    settled = true;
                } else {
                    awaitChange();
                    continue;
                }
            }
            settleFrameUses.run();
        }
    }

    /** Invoke all eligible retirement callbacks on the caller's session-progress thread. */
    public void progress() {
        while (true) {
            ResourceState state;
            synchronized (this) {
                state = callbacks.poll();
            }
            if (state == null) return;
            try {
                state.retired.run();
            } catch (Throwable failure) {
                failures.accept(failure);
            } finally {
                synchronized (this) {
                    state.phase = Phase.RETIRED;
                    resources.remove(state);
                    notifyAll();
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (!resources.isEmpty() || !callbacks.isEmpty()) {
            throw new IllegalStateException("resource generations remain live");
        }
        closed = true;
        owners.clear();
        acceptingOwners.clear();
    }

    private synchronized void seal(ResourceState state) {
        requireState(state);
        if (state.phase == Phase.SEALED) return;
        if (state.phase != Phase.CREATED) throw new IllegalStateException("resource has been dropped");
        state.phase = Phase.SEALED;
    }

    private synchronized void drop(ResourceState state) {
        if (state.phase == Phase.DROPPED || state.phase == Phase.RETIRED) return;
        requireState(state);
        state.phase = Phase.DROPPED;
        state.references--;
        scheduleIfRetired(state);
        notifyAll();
    }

    private void scheduleIfRetired(ResourceState state) {
        if (state.phase == Phase.DROPPED && state.references == 0 && !state.callbackQueued) {
            state.callbackQueued = true;
            callbacks.add(state);
        }
    }

    private synchronized void requireOwner(ContributionOwner owner) {
        if (owner == null || !owners.contains(owner)) {
            throw new IllegalArgumentException("resource owner is not registered with this session");
        }
    }

    private void requireState(ResourceState state) {
        if (state == null || state.directory != this || !resources.contains(state)) {
            throw new IllegalArgumentException("foreign or retired resource generation");
        }
    }

    private ResourceState requireReference(ResourceRef reference) {
        if (!(reference instanceof Reference concrete) || concrete.state.directory != this) {
            throw new IllegalArgumentException("resource belongs to another render session");
        }
        return concrete.state;
    }

    private void awaitChange() {
        try {
            wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while draining resource generations", interrupted);
        }
    }

    private enum Phase { CREATED, SEALED, DROPPED, RETIRED }

    static final class ResourceState {
        final ResourceDirectory directory;
        final ContributionOwner owner;
        final long identity;
        final Runnable retired;
        final ResourceRef reference;
        Phase phase = Phase.CREATED;
        int references = 1;
        boolean callbackQueued;

        ResourceState(ResourceDirectory directory, ContributionOwner owner,
                      long identity, Runnable retired) {
            this.directory = directory;
            this.owner = owner;
            this.identity = identity;
            this.retired = retired;
            reference = new Reference(this);
        }
    }

    private record Reference(ResourceState state) implements ResourceRef {
    }

    private static final class Generation implements ResourceGeneration {
        private final ResourceState state;

        Generation(ResourceState state) {
            this.state = state;
        }

        @Override public ResourceRef reference() { return state.reference; }
        @Override public void seal() { state.directory.seal(state); }
        @Override public void drop() { state.directory.drop(state); }
    }
}
