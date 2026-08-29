package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtToneLutOwnershipTest {
    @Test
    void lutRetainsTheSharedImageAllocationOwner() throws Exception {
        assertEquals(VmaImageAllocation.class,
                RtToneLut.class.getDeclaredField("imageAllocation").getType());
        assertFalse(hasField("image"));
        assertFalse(hasField("allocation"));
        assertFalse(hasField("vma"));
    }

    @Test
    void descriptorsAndDependentHandlesCloseBeforeTheImageOwner() throws Exception {
        String source = Files.readString(source());
        int unwind = source.indexOf("catch (Throwable t)");
        int unwindSamplerDescriptor = source.indexOf("samplerDescriptor.destroy();", unwind);
        int unwindSampledDescriptor = source.indexOf("sampledDescriptor.destroy();", unwind);
        int unwindSampler = source.indexOf("vkDestroySampler", unwind);
        int unwindView = source.indexOf("vkDestroyImageView", unwind);
        int unwindImage = source.indexOf("createdImage.close();", unwind);
        assertTrue(unwind >= 0 && unwindSamplerDescriptor > unwind
                && unwindSampledDescriptor > unwindSamplerDescriptor);
        assertTrue(unwindSampler > unwindSampledDescriptor && unwindView > unwindSampler
                && unwindImage > unwindView);

        int destroy = source.indexOf("public void destroy()");
        int samplerDescriptor = source.indexOf("samplerDescriptor.destroy();", destroy);
        int sampledDescriptor = source.indexOf("sampledDescriptor.destroy();", destroy);
        int sampler = source.indexOf("vkDestroySampler", destroy);
        int view = source.indexOf("vkDestroyImageView", destroy);
        int image = source.indexOf("imageAllocation.close();", destroy);

        assertTrue(destroy >= 0 && samplerDescriptor > destroy && sampledDescriptor > samplerDescriptor);
        assertTrue(sampler > sampledDescriptor && view > sampler && image > view);
    }

    private static boolean hasField(String name) {
        try {
            RtToneLut.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    private static Path source() {
        return Path.of("src/main/java/dev/comfyfluffy/caustica/renderer/presentation/RtToneLut.java");
    }
}
