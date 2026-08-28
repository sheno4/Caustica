package dev.comfyfluffy.caustica.api.pass;

/**
 * Creates one pass for one render session.
 *
 * @param <S> immutable setup supplied for the selected stage
 * @param <F> frame capabilities supplied when the pass records
 */
@FunctionalInterface
public interface PassFactory<S, F extends PassFrame> {
    /**
     * Create one pass. Raw resources remain factory-owned until this method returns successfully. If
     * construction throws, the factory releases its partial allocations because no {@link Pass#close()}
     * invocation can follow for an object which was never returned.
     */
    Pass<F> create(S setup);
}
