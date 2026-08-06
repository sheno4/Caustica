package dev.comfyfluffy.caustica.api.pass;

/**
 * A per-frame snapshot of a feature's {@code Option} values, keyed by option id. Immutable for the
 * duration of one frame: a value changed mid-frame is visible starting next frame, after the reload
 * class it was declared with (see {@code Reload}) has taken effect.
 *
 * <p>Storage and the settings UI are not wired up yet ({@code Option}/{@code Reload} are declared but
 * unread) — every lookup currently returns {@code fallback}. This exists now so passes read options
 * through one seam from the start, rather than needing a second migration once storage lands.
 */
public interface PassOptions {
    <T> T get(String optionId, T fallback);
}
