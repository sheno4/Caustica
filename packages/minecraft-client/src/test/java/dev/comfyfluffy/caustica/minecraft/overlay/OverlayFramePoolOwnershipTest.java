package dev.comfyfluffy.caustica.minecraft.overlay;

import dev.comfyfluffy.caustica.vulkan.VmaMappedHostBuffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void poolRetainsBuffersUntilFrameRetirement() throws Exception {
        var acquired = OverlayFramePool.class.getDeclaredField("acquired");

        assertTrue(Modifier.isFinal(acquired.getModifiers()));
        assertEquals(void.class,
                OverlayFramePool.class.getDeclaredMethod("endFrame",
                        dev.comfyfluffy.caustica.api.vulkan.GpuFrameUse.class).getReturnType());
    }

    @Test
    void poolPreservesUsageAndGpuCompletionRetirement() throws IOException {
        String source = Files.readString(Path.of(
                "packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/overlay/OverlayFramePool.java"));

        assertTrue(source.contains("VK_BUFFER_USAGE_VERTEX_BUFFER_BIT"));
        assertTrue(source.contains("VK_BUFFER_USAGE_INDEX_BUFFER_BIT"));
        assertTrue(source.contains("Math.max(bytes, MIN_SIZE)"));
        assertTrue(source.contains("VmaMappedHostBuffer.create(gpu, size, usage, label)"));
        assertTrue(source.contains("allocation.flush(offset, bytes)"));
        assertTrue(source.contains("use.whenComplete(() -> retired.forEach(Buffer::close))"));
        assertFalse(source.contains("vmaCreateBuffer"));
        assertFalse(source.contains("vmaDestroyBuffer"));
    }
}
