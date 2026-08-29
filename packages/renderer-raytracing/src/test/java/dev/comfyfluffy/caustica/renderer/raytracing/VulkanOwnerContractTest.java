package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class VulkanOwnerContractTest {
    @Test
    void programAndShaderBindingTableUseTheSharedMappedOwner() throws Exception {
        Class<?> implementationTable = Class.forName(RtProgramBackend.class.getName() + "$ImplementationTable");

        assertEquals(VmaMappedBuffer.class, implementationTable.getDeclaredField("storage").getType());
        assertEquals(VmaMappedBuffer.class, RtPipeline.class.getDeclaredField("sbt").getType());
        assertFalse(Arrays.stream(RtPipeline.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("SbtBuffer")));
    }
}
