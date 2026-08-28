package dev.comfyfluffy.caustica.api.pass;

/**
 * GPU work recorded at one engine-defined frame stage.
 *
 * <p>The frame type is the capability surface for that stage. A pre-trace pass receives the common
 * {@link PassFrame}; post effects receive {@link PostEffectFrame}; UI passes receive
 * {@link UiFrame}. The registration method, not the implementation class,
 * selects when the callback runs.
 *
 * <p>A pass has no resize or content-reload callbacks. It compares each frame with the state it built and
 * replaces resources when those facts differ.
 *
 * @param <F> frame capabilities available at the registered stage
 */
public interface Pass<F extends PassFrame> extends AutoCloseable {
    /**
     * Record this pass's work for one frame.
     *
     * <p>If this method throws, the host reports the exception, abandons the current frame command buffer,
     * stops future recording for this pass, resolves retirement callbacks belonging to the abandoned frame
     * once it cannot execute, and closes the pass after its earlier submitted uses drain.
     */
    void record(F frame);

    /**
     * Destroy resources owned exclusively by this pass instance.
     *
     * <p>Called once on the renderer thread, after every frame that recorded this pass has completed and
     * after its earlier retirement callbacks. It must not block or throw. Only this pass's scoped uses are
     * drained; the device and unrelated passes may still be active. The instance is not reused. The engine
     * owns this invocation; an extension stops a registered pass by closing its {@link PassRegistration},
     * not by calling this method itself.
     */
    @Override
    void close();
}
