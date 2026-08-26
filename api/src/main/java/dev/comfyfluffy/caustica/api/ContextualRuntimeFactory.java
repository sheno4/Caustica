package dev.comfyfluffy.caustica.api;

/** Creates one runtime contribution with access to the owning feature's activation context. */
@FunctionalInterface
public interface ContextualRuntimeFactory<T> {
    T create(FeatureRuntimeContext context);
}
