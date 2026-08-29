package dev.comfyfluffy.caustica.api.program;

import java.util.function.Function;

/**
 * Registers atomic, owner-scoped contributions to one render session's composed world program.
 *
 * <p>A declaration runs synchronously against a temporary {@link ProgramBuilder}. The builder issues the
 * typed ids returned through the declaration's export value, but none of its implementations are accepted
 * independently: returning from the declaration commits the whole set, and throwing abandons the whole set.
 * A rejected declaration does not take any definition retirement callback.
 *
 * <p>Compilation and publication remain asynchronous because all owners' accepted sets form one world
 * program. {@link ProgramRegistration#readiness()} reports whether this complete set became part of an
 * active composition. Accepted registrations have a deterministic logical order. Their observable outcomes
 * are the same as compiling and publishing each set independently in that order: a failing set publishes
 * none of its declarations, while unaffected later sets are retried against the last successful composition.
 * The renderer may combine compilation work internally, but a combined candidate's failure must be isolated
 * to the registration which introduces it and cannot fail an otherwise publishable registration.
 *
 * <p>Program ids are non-owning selection references. Closing the returned registration is the only removal
 * authority: it removes the complete set at a later program publication boundary. Geometry or scene bindings
 * which still name removed ids resolve to their documented fallbacks and do not keep the registration alive.
 *
 * <p>All channel, registration, and ticket methods are thread-safe. Declarations do not overlap for one
 * channel, so validation, acceptance order, and failure isolation are deterministic. Extension code is
 * invoked only by the synchronous {@code declaration} call and by the explicitly registered readiness
 * callbacks; the renderer never invokes the declaration again.
 */
public interface ProgramChannel {
    /**
     * Declare and atomically register one complete set of program implementations.
     *
     * <p>{@code declaration} must use the supplied builder only before it returns. Its non-null result is
     * exposed unchanged by {@link ProgramRegistration#exports()}, allowing a source-defined record to carry
     * all of the set's typed ids. If the declaration throws or returns {@code null}, no registration is
     * created, every issued id is abandoned, and every definition retirement callback remains caller-owned.
     *
     * <p>After synchronous acceptance, the registration owns every definition retirement callback. Each
     * callback is scheduled exactly once after the implementation is abandoned by failed compilation,
     * removed with the registration, or removed with the session, and no active or in-flight program can
     * execute it or read its implementation data. Callbacks never run inline with this method.
     *
     * @param declaration synchronous declaration of the set and its typed public exports
     * @param <E> source-defined export value, commonly an immutable record of program ids
     * @return the owner capability and readiness observation for the accepted set
     * @throws NullPointerException if {@code declaration} or its returned export value is null
     * @throws IllegalStateException if the context no longer accepts registrations or a qualified shader
     *         type conflicts with a live accepted declaration
     */
    <E> ProgramRegistration<E> register(Function<? super ProgramBuilder, ? extends E> declaration);
}
