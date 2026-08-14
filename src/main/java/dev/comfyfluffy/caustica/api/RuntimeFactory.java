package dev.comfyfluffy.caustica.api;

/** Creates one runtime-activation-owned contribution after RT has been requested. */
@FunctionalInterface
public interface RuntimeFactory<T> {
    T create();
}
