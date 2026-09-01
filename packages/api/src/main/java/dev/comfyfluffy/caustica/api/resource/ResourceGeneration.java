package dev.comfyfluffy.caustica.api.resource;

/**
 * Producer authority for one source-owned resource generation.
 *
 * <p>A new generation begins unsealed. The producer completes initialization required before the first
 * accepted read and then calls {@link #seal()}. Sealing fixes the generation's allocation graph and makes
 * its reference eligible for renderer acquisition. Retained source data such as mesh streams, material
 * tables, and binding or instance data remains unchanged until retirement; updates use a new generation.
 * A pass-owned dynamic target may change only when its API contract explicitly orders every read and write
 * through frame submission.
 *
 * <p>{@link #drop()} forbids future renderer acceptance of the reference and releases the producer's
 * lifetime claim. Existing renderer borrows remain valid. The retirement callback is later invoked exactly
 * once through render-session progress, after the last borrow ends. Neither sealing nor dropping invokes it
 * inline.
 */
public interface ResourceGeneration {
    /** Stable identity embedded in values which refer to this generation. */
    ResourceRef reference();

    /** Fix this generation's ownership graph and make it eligible for renderer acquisition. */
    void seal();

    /** Forbid future acquisition and release the producer's lifetime claim. */
    void drop();
}
