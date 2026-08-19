package dev.comfyfluffy.caustica.api;

/** Creates one contribution with access to the owning feature's runtime-activation context. */
@FunctionalInterface
public interface ContextualRuntimeFactory<T> {
    T create(FeatureRuntimeContext context);

    static <T> ContextualRuntimeFactory<T> from(RuntimeFactory<? extends T> factory) {
        java.util.Objects.requireNonNull(factory, "factory");
        return ignored -> factory.create();
    }
}
