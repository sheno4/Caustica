package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.engine.resource.ResourceLease;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Identity-deduplicated renderer borrows captured at one immutable-use boundary. */
final class ResourceLeaseSet implements AutoCloseable {
    private final IdentityHashMap<ResourceRef, ResourceLease> leases;
    private final Set<ResourceRef> unavailable;
    private boolean closed;

    private ResourceLeaseSet(IdentityHashMap<ResourceRef, ResourceLease> leases,
                             Set<ResourceRef> unavailable) {
        this.leases = leases;
        this.unavailable = unavailable;
    }

    static ResourceLeaseSet capture(Iterable<? extends ResourceRef> references) {
        IdentityHashMap<ResourceRef, ResourceLease> leases = new IdentityHashMap<>();
        Set<ResourceRef> unavailable = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (ResourceRef reference : references) {
                if (reference == ResourceRef.none() || leases.containsKey(reference)
                        || unavailable.contains(reference)) continue;
                var acquired = ResourceLease.tryAcquire(reference);
                if (acquired.isPresent()) leases.put(reference, acquired.get());
                else unavailable.add(reference);
            }
            return new ResourceLeaseSet(leases, unavailable);
        } catch (Throwable failure) {
            RtRetainedSceneBackend.closeAll(leases.values(), failure);
            throw failure;
        }
    }

    static ResourceLeaseSet acquireRequired(Iterable<? extends ResourceRef> references) {
        ResourceLeaseSet captured = capture(references);
        if (!captured.unavailable.isEmpty()) {
            captured.close();
            throw new IllegalStateException("required resource generation has been dropped");
        }
        return captured;
    }

    synchronized boolean available(ResourceRef reference) {
        requireOpen();
        return reference == ResourceRef.none() || leases.containsKey(reference);
    }

    synchronized ResourceLeaseSet retainOnly(Iterable<? extends ResourceRef> references) {
        requireOpen();
        IdentityHashMap<ResourceRef, ResourceLease> retained = new IdentityHashMap<>();
        try {
            for (ResourceRef reference : references) {
                if (reference == ResourceRef.none() || retained.containsKey(reference)) continue;
                ResourceLease lease = leases.get(reference);
                if (lease == null) throw new IllegalStateException("resource was not captured by this frame");
                retained.put(reference, lease.retain());
            }
            return new ResourceLeaseSet(retained,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        } catch (Throwable failure) {
            RtRetainedSceneBackend.closeAll(retained.values(), failure);
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("resource lease set is closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        List<ResourceLease> released = new ArrayList<>(leases.values());
        leases.clear();
        RtRetainedSceneBackend.closeAll(released, null);
    }
}
