package dev.comfyfluffy.caustica.settings;

/**
 * A live read view of one feature's option values.
 *
 * <p>Read values with the declared {@link Option} token. Passing an undeclared or unequal token throws.
 * Use {@link OptionLookup#snapshot()} when several reads must observe one consistent value set.
 */
public interface OptionValues {
    <T> T get(Option<T> option);
}
