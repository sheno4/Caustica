package dev.comfyfluffy.caustica.api;

/**
 * Marker for immutable, opaque identities the renderer issues for retained objects.
 *
 * <p>An extension may freely copy an id reference, share it between threads, and use it as a key in an
 * ordinary hash-based collection. Copying the reference still names the same retained object; it neither
 * duplicates that object nor extends its renderer-managed lifetime.
 *
 * <p>Equality identifies the same issued object. Equality and hash codes remain stable for the lifetime
 * of the Java id, and two distinct issued objects never compare equal, even if the renderer reuses their
 * internal storage. Hash codes need not be unique and have no meaning outside the running process.
 *
 * <p>There is nothing to read, construct, serialize, or order. The renderer may represent an id however it
 * chooses, and each subinterface's issuing channel defines when that id stops resolving.
 *
 * <p>Nothing stops an extension from implementing one of these subinterfaces. Such an implementation is
 * inert: the renderer resolves ids against its own tables and rejects anything it did not issue.
 */
public interface RetainedId {
}
