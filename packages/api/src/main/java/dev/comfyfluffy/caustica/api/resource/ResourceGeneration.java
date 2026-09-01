package dev.comfyfluffy.caustica.api.resource;

/**
 * Producer authority for one immutable resource generation.
 *
 * <p>A new generation begins unsealed. The producer finishes populating every byte transitively reachable
 * through the resource and then calls {@link #seal()}. Sealing asserts that those bytes remain unchanged
 * until the retirement callback runs. Updating a resource therefore requires a new generation; unchanged
 * generations may be reused by any number of accepted objects and updates from the same contribution.
 *
 * <p>{@link #drop()} forbids future renderer acceptance of the reference and releases the producer's
 * lifetime claim. Existing renderer borrows remain valid. The retirement callback is later invoked exactly
 * once through render-session progress, after the last borrow ends. Neither sealing nor dropping invokes it
 * inline.
 */
public interface ResourceGeneration {
    /** Stable identity embedded in values which refer to this generation. */
    ResourceRef reference();

    /** Make this generation immutable and eligible for renderer acquisition. */
    void seal();

    /** Forbid future acquisition and release the producer's lifetime claim. */
    void drop();
}
