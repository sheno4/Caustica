package dev.comfyfluffy.caustica.api.session;

/** Final lifetime hooks for one factory-created render-session contribution. */
public interface RenderSessionContribution extends AutoCloseable {
    RenderSessionContribution EMPTY = new RenderSessionContribution() { };

    /**
     * Stops producers and joins their threads. The host has already stopped frame callbacks and refuses
     * new registrations, but existing identities and retained submission/drop operations remain valid
     * while this method runs. Nothing may be submitted by this contribution after it returns. Called at
     * most once.
     */
    default void stop() {
    }

    /**
     * Releases shared CPU and GPU state. Before this call, scoped objects have been dropped, accepted work
     * has drained, and every program-ticket and retirement callback has returned. No session callback can
     * reach the contribution after this method starts. This is not a device-wide idle boundary; it only follows
     * drainage of work and objects in this contribution scope. Called at most once and only if the factory
     * successfully returned this contribution.
     */
    @Override
    default void close() {
    }
}
