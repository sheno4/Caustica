package dev.comfyfluffy.caustica.api.material;

import dev.comfyfluffy.caustica.api.CausticaApi;

import java.util.Objects;

/**
 * Registered materials, reached from {@link CausticaApi#materials()}.
 *
 * <p>There is no material epoch. A material is usable from the moment it is registered and stays usable
 * until it is dropped — the same contract as retained geometry, and the reason a resource reload is not a
 * global event: register the new materials, resubmit the affected meshes in one batch, drop the old ones.
 * Both sets are live in between, so no frame ever references something that has been taken away.
 */
public interface MaterialChannel {
    /**
     * Register a material and return an id usable immediately.
     *
     * <p>Synchronous on purpose. A material is a table entry, not acceleration work, so there is nothing to
     * defer — and immediacy is what removes cross-channel ordering as a concept: geometry submitted after
     * this returns can always name the result.
     */
    MaterialId register(MaterialDefinition definition);

    /**
     * Stop using a material, and learn when whatever the source associated with it is free.
     *
     * <p>Nothing is freed at the call. {@code retired} runs once no retained geometry names the material
     * and no submitted GPU work still reads it — which is the point of the call, because the renderer's own
     * material entry is a few words and costs nothing to hold, while the source's textures, parameter
     * buffers and descriptor ranges are the expensive part and only the source knows what they are.
     *
     * <p>Dropping while geometry still names it is not an error and is not rejected: the drop is honoured
     * from the source's point of view and the callback simply arrives later, when the last mesh naming it
     * goes. A source that never drops that geometry never gets the callback.
     */
    void drop(MaterialId material, Runnable retired);
}
