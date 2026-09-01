package dev.comfyfluffy.caustica.api.resource;

/** Contribution-owned creation and lifetime authority for immutable resource generations. */
public interface ResourceChannel {
    /**
     * Create an unsealed generation carrying one producer lifetime claim.
     *
     * <p>Creation synchronously transfers the callback to the render session. If the generation is never
     * used, dropping it still schedules the callback through normal session progress.
     */
    ResourceGeneration create(Runnable retired);

    /** Create a generation for which the producer requires no retirement notification. */
    default ResourceGeneration create() {
        return create(() -> { });
    }
}
