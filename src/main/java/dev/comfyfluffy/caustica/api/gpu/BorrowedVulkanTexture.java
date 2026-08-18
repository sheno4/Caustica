package dev.comfyfluffy.caustica.api.gpu;

import dev.comfyfluffy.caustica.api.provider.TextureResource;
import org.lwjgl.vulkan.VK10;

import java.util.Objects;

/**
 * Optional zero-copy provider texture backed by a provider-owned Vulkan image view.
 *
 * <p>This supported provider resource is available to every extension; it is not a host-only shortcut.
 * The provider keeps the image and view alive, and the image in {@link #imageLayout()}, until
 * {@link #retired()} is invoked. The renderer invokes that callback exactly once after its descriptor table
 * and submitted GPU work can no longer reference the view. The renderer never destroys the image or view.
 * The supplied image view must return linear RGB values when sampled; use an sRGB view for sRGB-encoded
 * storage and a UNORM or floating-point view for linear storage.
 */
public record BorrowedVulkanTexture(long imageView, int imageLayout, Runnable retired)
        implements TextureResource {
    public BorrowedVulkanTexture {
        if (imageView == 0L) throw new IllegalArgumentException("imageView must not be VK_NULL_HANDLE");
        if (imageLayout != VK10.VK_IMAGE_LAYOUT_GENERAL
                && imageLayout != VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            throw new IllegalArgumentException("borrowed texture must be in a shader-readable image layout");
        }
        Objects.requireNonNull(retired, "retired");
    }
}
