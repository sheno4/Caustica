package dev.comfyfluffy.caustica.api.pass;

/**
 * The passes currently recording in one render session.
 *
 * <p>This channel is scoped to one render session. A stage factory receives the immutable setup for that
 * session and creates exactly one pass. Closing the returned registration stops future recording; the
 * engine calls {@link Pass#close()} after every submitted use of that pass has drained. The
 * render session does the same for registrations that remain open when the session ends.
 *
 * <p>The registration method selects the engine stage. The generic pass type only couples that stage to
 * the frame capabilities valid there; it does not expose an open-ended render graph.
 * Before every callback the renderer binds the session's shared resource and sampler descriptor heaps.
 * Passes consume heap indices from their frame or their own allocations and leave those bindings intact.
 * A factory failure leaves no registration and follows {@link PassFactory}'s partial-resource rule.
 */
public interface PassChannel {
    /**
     * Record before the trace, for resources that world shaders consume: dirty texture, environment,
     * lookup-table, or buffer updates. The callback may record nothing when asynchronously prepared source
     * data has not changed. Accepted retained changes are consumed independently at renderer publication
     * boundaries. Order between pre-trace passes is meaningless; do not depend on it.
     */
    PassRegistration addWorldResourcePass(PassFactory<WorldResourceSetup, PassFrame> factory);

    /**
     * Record after reconstruction and before the display transform. Effects compose in registration
     * order, but must work when surrounding effects are absent. A pass joins the scene-colour chain for a
     * frame only by acquiring and fully writing its output.
     */
    PassRegistration addPostEffectPass(PassFactory<PostEffectSetup, PostEffectFrame> factory);

    /**
     * Record after the display transform into the display-resolution UI layer. The layer remains separate
     * from the scene input consumed by reconstruction and frame generation; a recorded layer may therefore
     * be reused for more than one presented frame.
     */
    PassRegistration addUiPass(PassFactory<UiSetup, UiFrame> factory);
}
