package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import dev.comfyfluffy.caustica.vulkan.VmaMappedHostBuffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MinecraftMaterialUploadOwnershipTest {
    @Test
    void uploadedImagesAndStagingBuffersUseSharedOwners() throws Exception {
        Class<?> image = nested("Image");
        Class<?> imageUpload = nested("ImageUpload");

        assertEquals(VmaImageAllocation.class, image.getDeclaredField("allocation").getType());
        assertEquals(VmaMappedHostBuffer.class, imageUpload.getRecordComponents()[2].getType());
        assertEquals(MinecraftProgramResources.Epoch.class,
                MinecraftMaterialUploadPass.class.getDeclaredField("epoch").getType());
        assertFalse(Arrays.stream(MinecraftMaterialUploadPass.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("StagingBuffer")));
    }

    private static Class<?> nested(String simpleName) {
        return Arrays.stream(MinecraftMaterialUploadPass.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(simpleName))
                .findFirst().orElseThrow();
    }
}
