package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class GltfMeshUploaderOwnershipTest {
    @Test
    void uploadedPrimitiveRetainsIndependentResourceGenerations() {
        Class<?> uploadedPrimitive = Arrays.stream(GltfMeshUploader.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("UploadedPrimitive"))
                .findFirst().orElseThrow();
        Class<?>[] componentTypes = Arrays.stream(uploadedPrimitive.getRecordComponents())
                .map(component -> component.getType())
                .toArray(Class<?>[]::new);
        Class<?> ownedBuffer = Arrays.stream(GltfMeshUploader.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("OwnedBuffer"))
                .findFirst().orElseThrow();

        assertSame(ownedBuffer, componentTypes[0]);
        assertSame(ownedBuffer, componentTypes[1]);
        assertSame(ownedBuffer, componentTypes[2]);
        assertEquals(VmaMappedBuffer.class, ownedBuffer.getRecordComponents()[0].getType());
        assertEquals(ResourceGeneration.class, ownedBuffer.getRecordComponents()[1].getType());
    }
}
