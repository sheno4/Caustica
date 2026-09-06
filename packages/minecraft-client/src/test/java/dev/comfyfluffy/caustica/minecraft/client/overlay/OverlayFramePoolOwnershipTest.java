package dev.comfyfluffy.caustica.minecraft.client.overlay;

import dev.comfyfluffy.caustica.vulkan.VmaMappedHostBuffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OverlayFramePoolOwnershipTest {
    @Test
    void pooledBufferDelegatesMappedAllocationOwnership() throws Exception {
        var allocation = OverlayFramePool.Buffer.class.getDeclaredField("allocation");

        assertEquals(VmaMappedHostBuffer.class, allocation.getType());
        assertTrue(Modifier.isFinal(allocation.getModifiers()));
        assertEquals(ByteBuffer.class, OverlayFramePool.Buffer.class.getDeclaredMethod("mapped").getReturnType());
        assertFalse(Arrays.stream(OverlayFramePool.Buffer.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == long.class));
    }

}
