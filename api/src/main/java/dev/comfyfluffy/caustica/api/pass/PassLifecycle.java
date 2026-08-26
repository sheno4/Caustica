package dev.comfyfluffy.caustica.api.pass;

/**
 * The one thing a pass cannot work out for itself: that the device is about to go away.
 *
 * <p>Everything else a lifecycle used to announce, the extension already knows or can see. It called
 * {@link PassChannel} to add the pass, so it knows when the pass started; it can read
 * {@link dev.comfyfluffy.caustica.api.CausticaApi#gpu()} whenever it likes, so it needs no handles posted
 * to it; and anything that varies — the render resolution, the host's content, its own settings — it
 * compares at the top of {@code record} against what it last built from, which is the same shape as
 * deciding whether to do any work at all that frame.
 *
 * <p>That check is not merely equivalent to a callback, it is stricter. A resize callback cannot catch a
 * render resolution that moved because the upscaler's quality mode changed, and a content callback cannot
 * catch an extension reloading its own assets. Comparing against what you built from catches every case,
 * including the ones nobody thought to fire an event for.
 *
 * <p>What remains is a window, not a notification. When the renderer is about to destroy the device, an
 * extension's Vulkan objects have to be destroyed first, and nothing it can poll will tell it that moment
 * arrived.
 */
public interface PassLifecycle {
    /**
     * Destroy everything this pass owns, now. Called when the pass is removed from {@link PassChannel} or
     * the render session ends, whichever comes first.
     *
     * <p>Unconditional: no frame that recorded this pass is still executing, so nothing here needs
     * {@link dev.comfyfluffy.caustica.api.gpu.GpuDevice#retireAfterUse}. That is the opposite of every
     * other point in a pass's life, where a resource being wrong is never the same instant as it being
     * free.
     *
     * <p>Not the end of the instance. A pass that was not removed stays registered, and the next session
     * records it again — so leave the object able to rebuild rather than assuming it is finished.
     */
    void destroy();
}
