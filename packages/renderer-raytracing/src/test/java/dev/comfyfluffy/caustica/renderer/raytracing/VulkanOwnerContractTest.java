package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VulkanOwnerContractTest {
    @Test
    void programAndShaderBindingTableUseTheSharedMappedOwner() throws Exception {
        Class<?> candidate = Class.forName(RtProgramBackend.class.getName() + "$Candidate");

        assertEquals(VmaMappedBuffer.class, candidate.getDeclaredField("table").getType());
        assertEquals(VmaMappedBuffer.class, RtPipeline.class.getDeclaredField("sbt").getType());
    }
}
