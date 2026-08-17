package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.OptionValues;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;

/**
 * Passed to {@link CausticaRenderPass#create} and {@link CausticaRenderPass#resize}. A pass allocates its
 * own {@link GpuImage}/{@link GpuBuffer} resources through
 * {@link #device()} and owns their lifetime — the engine does not track or retire them.
 */
public interface PassSetup {
    /** Supported pass-local Vulkan device and resource-allocation services. */
    GpuDevice device();

    int displayWidth();

    int displayHeight();

    /**
     * Hand the engine a pass-produced image the world ray-tracing pipeline's own shaders sample, at the
     * binding {@code name} the pass's own Slang declares (a {@code [[vk::binding(N, 2)]]} in a module the
     * pass owns). The engine discovers the binding index from the active composition's own reflection;
     * it never declares this slot itself. At most one pass may publish a given
     * name. The image must stay valid — and its identity stable across a resize unless republished — for
     * as long as this pass is active.
     */
    void publishWorldResource(String name, GpuImage image, long sampler);

    /**
     * Same as {@link #publishWorldResource(String, GpuImage, long)}, for a pass that declared a
     * {@code StructuredBuffer}/{@code RWStructuredBuffer}/{@code ConstantBuffer} binding instead of a
     * sampler — reflected the same way, at the same set, just a different Vulkan descriptor kind. No
     * sampler; a buffer binding doesn't have one.
     */
    void publishWorldResource(String name, GpuBuffer buffer);

    /**
     * This feature's current option values, for a decision {@link CausticaRenderPass#create}/
     * {@link CausticaRenderPass#resize} has to make once rather than every frame (e.g. sizing an image
     * pyramid) — unlike {@link PassFrame#options()}, this is not frozen for a frame, since create/resize
     * are not per-frame calls; it reads whatever is current when called.
     */
    OptionValues options();
}
