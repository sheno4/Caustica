package dev.comfyfluffy.caustica.minecraft.client.overlay;

import dev.comfyfluffy.caustica.api.resource.FrameResources;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaMappedHostBuffer;

import static org.lwjgl.vulkan.VK10.*;

/** Allocates host-visible vertex and index buffers retained by the UI frame. */
final class OverlayFrameBuffers {
    private static final long MIN_SIZE = 256;
    private final ResourceFactory resources;
    private final FrameResources frame;

    OverlayFrameBuffers(ResourceFactory resources, FrameResources frame) {
        this.resources = resources;
        this.frame = frame;
    }

    VmaMappedHostBuffer acquireVertex(GpuDevice gpu, long bytes, String label) {
        return acquire(gpu, bytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, label);
    }

    VmaMappedHostBuffer acquireIndex(GpuDevice gpu, long bytes, String label) {
        return acquire(gpu, bytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, label);
    }

    private VmaMappedHostBuffer acquire(GpuDevice gpu, long bytes, int usage, String label) {
        var buffer = VmaMappedHostBuffer.create(gpu, Math.max(bytes, MIN_SIZE), usage, label);
        ResourceOwner owner;
        try {
            owner = resources.create(buffer::close);
        } catch (RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, buffer::close);
            throw failure;
        }
        try (owner) {
            frame.retain(owner);
        }
        return buffer;
    }
}
