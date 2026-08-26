package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.api.option.OptionValues;
import dev.comfyfluffy.caustica.api.shader.ShaderCompiler;

/**
 * The services available to a pass at an epoch boundary, and the extent to which the renderer knows the
 * pass at all.
 *
 * <p>Every {@link PassLifecycle} callback that can allocate receives one of these, which is the whole
 * point: a pass rebuilding a resource because the display resized or the resource pack changed needs the
 * same handles it had when it first allocated. A pass owns every {@link GpuImage} and {@link GpuBuffer}
 * it creates here — the renderer does not track or retire them.
 *
 * <p>Subtypes add what a particular kind of pass may additionally do:
 * {@link WorldResourceSetup} can publish into the world pipeline;
 * {@code UiSetup} exposes the UI layer's format.
 */
public interface PassSetup {
    /** Vulkan device, allocator, and resource factories. */
    GpuDevice device();

    /** Host compiler for this pass's own shaders. */
    ShaderCompiler shaderCompiler();

    int displayWidth();

    int displayHeight();

    /**
     * This feature's current option values, for a decision made once per epoch rather than every frame
     * (sizing an image pyramid, choosing a format). Not frozen the way a frame's view is: an epoch
     * callback is not a per-frame call, so this reads whatever is current.
     */
    OptionValues options();
}
