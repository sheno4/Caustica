package dev.comfyfluffy.caustica.engine.pass;

/** Receives an isolated pass callback or lifecycle failure. */
@FunctionalInterface
public interface PassFailureHandler {
    void failed(PassKey pass, Throwable failure);
}
