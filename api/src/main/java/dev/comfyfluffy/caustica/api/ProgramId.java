package dev.comfyfluffy.caustica.api;

/**
 * Marker for the opaque identities the renderer issues for things compiled into the active world program —
 * surface implementations, emission profiles.
 *
 * <p>Deliberately empty, for the reasons {@link RetainedId} is. What separates the two is lifetime, and it
 * is worth having in the type system: a {@link RetainedId} lives inside a scene generation, while one of
 * these lives inside a <b>runtime activation</b>. The compiled closure changes with feature selection, so
 * every id here dies when the program is rebuilt and none survives into the next activation.
 *
 * <p>These exist because a {@link ResourceId} is a registration name, and a name in a submitted record is a
 * string the renderer must resolve on every submission and can only reject there. Resolving once turns that
 * into a lookup at registration time and an issued handle afterwards — which is also what lets a record
 * naming a surface be validated when it is built rather than when it is read.
 */
public interface ProgramId {
}
