package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;

/**
 * Passed to {@link CausticaRenderPass#create} and {@link CausticaRenderPass#resize}. A pass allocates its
 * own {@link GpuImage}/{@link dev.comfyfluffy.caustica.rt.accel.GpuBuffer} resources through
 * {@link #context()} and owns their lifetime — the engine does not track or retire them.
 */
public interface PassSetup {
    /** Raw engine context: device, allocator, image/buffer creation, debug labelling. */
    GpuContext context();

    int displayWidth();

    int displayHeight();

    /**
     * Hand the engine a pass-produced image the world ray-tracing pipeline's own shaders sample, at the
     * binding {@code name} the pass's own Slang declares (a {@code [[vk::binding(N, 2)]]} in a module the
     * pass owns — see {@code caustica_lut_sky_bindings.slang} for the shape). The engine discovers the
     * binding index from the active composition's own reflection; it never declares this slot itself,
     * unlike the old fixed {@code EngineImage} enum this replaces. At most one pass may publish a given
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
     * Hand the engine a pass-produced image other Java code reads back by name — e.g. bloom's base
     * level, read by the display-mapping pipeline. Unlike {@link #publishWorldResource}, this never
     * crosses into a Slang-visible descriptor: it is a plain handoff for a pass whose output a
     * different *pipeline* (not the world ray-tracing pipeline) consumes directly. {@code levelCount} is
     * the total mip/pyramid depth for a pass that publishes a pyramid's base level (e.g. bloom); pass 1
     * for a single image.
     */
    void publishOutput(String name, GpuImage image, int levelCount);

    /**
     * This feature's current option values, for a decision {@link CausticaRenderPass#create}/
     * {@link CausticaRenderPass#resize} has to make once rather than every frame (e.g. sizing an image
     * pyramid) — unlike {@link PassFrame#options()}, this is not frozen for a frame, since create/resize
     * are not per-frame calls; it reads whatever is current when called.
     */
    PassOptions options();
}
