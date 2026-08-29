package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GltfMeshUploaderOwnershipTest {
    @Test
    void uploadedPrimitiveRetainsSharedMappedBufferOwners() {
        Class<?> uploadedPrimitive = Arrays.stream(GltfMeshUploader.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("UploadedPrimitive"))
                .findFirst().orElseThrow();
        Class<?>[] componentTypes = Arrays.stream(uploadedPrimitive.getRecordComponents())
                .map(component -> component.getType())
                .toArray(Class<?>[]::new);

        assertEquals(VmaMappedBuffer.class, componentTypes[0]);
        assertEquals(VmaMappedBuffer.class, componentTypes[1]);
        assertEquals(VmaMappedBuffer.class, componentTypes[2]);
    }
}
