package dev.comfyfluffy.caustica.rt;

/** Renderer-owned Vulkan image allocation exposed to passes only as a borrowed API image. */
public interface GpuImage extends dev.comfyfluffy.caustica.api.gpu.GpuImage {
    /** Destroy the view, descriptor entries, and allocation after every GPU use has drained. */
    void destroy();
}
