package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassSetup;

/**
 * A read view of one {@link Feature}'s {@link Option} values, backed by {@code CausticaOptions}.
 *
 * <p>Lives next to {@link Option} rather than under {@code api.pass} because a render pass is only one
 * kind of reader: the same view serves a feature whose options steer a provider, or a settings screen
 * reading values before any GPU context exists.
 *
 * <p>Reads go through the {@link Option} token the feature declared, not a string id: the token carries
 * its own type and its own default, so a call site can neither mistype a key nor restate a default that
 * could drift from the declaration. Passing an {@link Option} the owning feature never declared — or one
 * that isn't {@code equals} to the declared one — throws, so a mismatch fails loudly at the call site
 * instead of silently resolving to something else.
 *
 * <p>Whether a view is live or frozen depends on where it came from: {@link PassFrame#options()} is
 * frozen for the whole frame (a value changed mid-frame becomes visible next frame, after the
 * {@link Reload} class it was declared with has taken effect), while {@link PassSetup#options()} and
 * {@code CausticaOptions#options} read whatever is current.
 */
public interface OptionValues {
    <T> T get(Option<T> option);
}
