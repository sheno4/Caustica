package dev.comfyfluffy.caustica.vulkan;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VmaMappedBufferSharingTest {
    @Test
    void distinctAsyncFamiliesUseConcurrentSharing() {
        assertArrayEquals(new int[] { 2, 7 },
                VmaMappedBuffer.asyncSharingFamilies(new int[] { 2, 7 }));
    }

    @Test
    void oneAsyncFamilyKeepsExclusiveSharing() {
        assertArrayEquals(new int[0], VmaMappedBuffer.asyncSharingFamilies(new int[] { 4 }));
    }

    @Test
    void asyncSharingRejectsAnEmptyFamilySet() {
        assertThrows(IllegalArgumentException.class,
                () -> VmaMappedBuffer.asyncSharingFamilies(new int[0]));
    }
}
