package dev.comfyfluffy.caustica.api.pass;

/** GPU commands recorded into a host-owned frame with completion-scoped resources. */
public interface Pass<F extends PassFrame> extends AutoCloseable {
    void record(F frame);
    @Override void close();
}
