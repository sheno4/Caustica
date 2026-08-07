package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.Option;

/**
 * A read view of one feature's {@code Option} values, backed by {@code CausticaOptions}.
 *
 * <p>Reads go through the {@link Option} token the feature declared, not a string id: the token carries
 * its own type and its own default, so a call site can neither mistype a key nor restate a default that
 * could drift from the declaration. Passing an {@link Option} the owning feature never declared — or one
 * that isn't {@code equals} to the declared one — throws, so a mismatch fails loudly at the call site
 * instead of silently resolving to something else.
 *
 * <p>Whether a view is live or frozen depends on where it came from: {@link PassFrame#options()} is
 * frozen for the whole frame (a value changed mid-frame becomes visible next frame, after the
 * {@code Reload} class it was declared with has taken effect), while {@link PassSetup#options()} and
 * {@code RenderPassManager#optionsForFeature} read whatever is current.
 */
public interface PassOptions {
    <T> T get(Option<T> option);
}
