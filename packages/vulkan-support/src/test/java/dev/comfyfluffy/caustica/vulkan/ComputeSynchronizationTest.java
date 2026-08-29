package dev.comfyfluffy.caustica.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK13;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ComputeSynchronizationTest {
    @Test
    void computeImagesUseOnlyStorageAccessScopes() {
        assertEquals(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, ComputeSynchronization.COMPUTE);
        assertEquals(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, ComputeSynchronization.STORAGE_ACCESS);
        assertEquals(0L, ComputeSynchronization.STORAGE_ACCESS & VK13.VK_ACCESS_2_SHADER_READ_BIT);
        assertEquals(0L, ComputeSynchronization.STORAGE_ACCESS & VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
    }
}
