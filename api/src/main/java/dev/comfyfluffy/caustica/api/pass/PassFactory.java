package dev.comfyfluffy.caustica.api.pass;

/**
 * Creates one pass for one render session.
 *
 * @param <S> immutable setup supplied for the selected stage
 * @param <F> frame capabilities supplied when the pass records
 */
@FunctionalInterface
public interface PassFactory<S, F extends PassFrame> {
    Pass<F> create(S setup);
}
