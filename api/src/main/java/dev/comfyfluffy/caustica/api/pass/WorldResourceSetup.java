package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;

/**
 * {@link PassSetup} for a {@link WorldResourcePass}, adding the one thing that kind of pass exists to do:
 * hand the world ray-tracing pipeline something to read.
 *
 * <p>Publishing is an epoch-scoped act, not a per-frame one. A published handle stays bound until it is
 * republished or the pass is disabled, so the ordinary case is to publish once in
 * {@link PassLifecycle#activated} and never think about it again. Republish from
 * {@link PassLifecycle#displayResized} or {@link PassLifecycle#resourcePackApplied} when the resource is
 * rebuilt.

 * <p>Named bindings are what the descriptor heap retires. A pass whose resources reach the shader by
 * device address publishes nothing here: it puts the address in the word its implementation already reads,
 * and replaces the buffer rather than republishing a handle.
 */
public interface WorldResourceSetup extends PassSetup {
    /**
     * Bind an image the world pipeline's shaders sample, at the binding {@code name} this pass's own Slang
     * declares (a {@code [[vk::binding(N, 2)]]} in a module the pass owns). The engine reads the binding
     * index out of the active composition's reflection; it never declares the slot itself, and it never
     * interprets the contents. At most one pass may publish a given name.
     *
     * <p>Raw handles rather than {@link GpuImage} on purpose: the pass owns whatever it publishes, and it
     * may have come from {@link GpuDevice#createStorageImage} or straight from
     * {@link GpuDevice#vmaAllocator()}. Publish an engine-created image as
     * {@code publishWorldTexture(name, image.view(), VK_IMAGE_LAYOUT_GENERAL, sampler)}.
     */
    void publishWorldTexture(String name, long imageView, int imageLayout, long sampler);

    /**
     * Same as {@link #publishWorldTexture} for a pass that declared a {@code StructuredBuffer},
     * {@code RWStructuredBuffer}, or {@code ConstantBuffer} instead — reflected the same way, at the same
     * set, just a different descriptor kind and no sampler.
     *
     * <p>Publish an engine-created buffer as {@code publishWorldBuffer(name, buffer.handle(), 0,
     * buffer.size())}; one suballocated from the pass's own VMA arena passes its own offset and size.
     * {@link GpuBuffer} is deliberately not accepted, for the reason above.
     */
    void publishWorldBuffer(String name, long buffer, long offset, long size);
}
