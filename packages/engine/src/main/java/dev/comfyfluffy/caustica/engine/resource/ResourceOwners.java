package dev.comfyfluffy.caustica.engine.resource;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;

import java.util.IdentityHashMap;

/** Strong ownership of the distinct resource graphs reachable from one immutable value. */
public final class ResourceOwners implements AutoCloseable {
    private final IdentityHashMap<ResourceRef, ResourceOwner> owners = new IdentityHashMap<>();

    public static ResourceOwners capture(Iterable<? extends ResourceRef> references) {
        ResourceOwners result = new ResourceOwners();
        try {
            for (ResourceRef reference : references) {
                if (reference != ResourceRef.none() && !result.owners.containsKey(reference)) {
                    result.owners.put(reference, reference.retain());
                }
            }
            return result;
        } catch (Throwable failure) {
            result.close();
            throw failure;
        }
    }

    /** Borrow a captured claim while this collection remains retained. */
    public synchronized ResourceOwner borrowed(ResourceRef reference) { return owners.get(reference); }

    @Override public synchronized void close() {
        owners.values().forEach(ResourceOwner::close);
        owners.clear();
    }
}
