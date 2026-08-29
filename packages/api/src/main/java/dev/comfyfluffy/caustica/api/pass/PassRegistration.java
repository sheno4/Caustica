package dev.comfyfluffy.caustica.api.pass;

/** A session-scoped pass registration. Closing is non-blocking and idempotent. */
public interface PassRegistration extends AutoCloseable {
    /** Stop future recording. The pass is closed after its submitted GPU uses drain. */
    @Override
    void close();
}
