package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDescriptorHeap;
import org.lwjgl.vulkan.VkDescriptorMappingSourcePushIndexEXT;
import org.lwjgl.vulkan.VkDescriptorSetAndBindingMappingEXT;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtPipelineSpirvAbiTest {
    @Test
    void acceptsAHeapNativeModuleWithoutSetDecorations() {
        RtShaderCode shader = new RtShaderCode("heap-native", words(1 << 16));
        assertDoesNotThrow(() -> RtPipeline.requireDescriptorHeapCompatible(shader));
    }

    @Test
    void acceptsOnlyTheMappedWorldTlasDescriptorBinding() {
        assertDoesNotThrow(() -> RtPipeline.requireDescriptorHeapCompatible(
                new RtShaderCode("world-tlas", descriptorBinding(7, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> RtPipeline.requireDescriptorHeapCompatible(
                new RtShaderCode("wrong-set", descriptorBinding(7, 1, 0))));
        assertThrows(IllegalArgumentException.class, () -> RtPipeline.requireDescriptorHeapCompatible(
                new RtShaderCode("wrong-binding", descriptorBinding(7, 0, 1))));
        assertThrows(IllegalArgumentException.class, () -> RtPipeline.requireDescriptorHeapCompatible(
                new RtShaderCode("incomplete", decoration(7, 34, 0))));
    }

    @Test
    void tlasMappingReadsThePublishedIndexFromWorldPushData() {
        RtPipeline.TlasPushIndexMapping mapping = RtPipeline.tlasPushIndexMapping(64);
        assertEquals(0, mapping.descriptorSet());
        assertEquals(0, mapping.binding());
        assertEquals(32, mapping.pushOffset());
        assertEquals(64, mapping.heapIndexStride());
    }

    @Test
    void tlasMappingUsesTheHeapPushIndexSource() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAndBindingMappingEXT mapping = VkDescriptorSetAndBindingMappingEXT.calloc(stack);
            RtPipeline.tlasPushIndexMapping(64).write(mapping);
            assertEquals(0, mapping.descriptorSet());
            assertEquals(0, mapping.firstBinding());
            assertEquals(1, mapping.bindingCount());
            assertEquals(EXTDescriptorHeap.VK_SPIRV_RESOURCE_TYPE_ACCELERATION_STRUCTURE_BIT_EXT,
                    mapping.resourceMask());
            assertEquals(EXTDescriptorHeap.VK_DESCRIPTOR_MAPPING_SOURCE_HEAP_WITH_PUSH_INDEX_EXT,
                    mapping.source());
            VkDescriptorMappingSourcePushIndexEXT pushIndex = mapping.sourceData().pushIndex();
            assertEquals(0, pushIndex.heapOffset());
            assertEquals(32, pushIndex.pushOffset());
            assertEquals(64, pushIndex.heapIndexStride());
            assertEquals(64, pushIndex.heapArrayStride());
        }
    }

    @Test
    void rejectsMalformedInstructionRanges() {
        assertThrows(IllegalArgumentException.class, () -> RtPipeline.requireDescriptorHeapCompatible(
                new RtShaderCode("malformed", words((4 << 16) | 1))));
    }

    private static byte[] descriptorBinding(int target, int descriptorSet, int binding) {
        return words((4 << 16) | 71, target, 34, descriptorSet,
                (4 << 16) | 71, target, 33, binding);
    }

    private static byte[] decoration(int target, int decoration, int value) {
        return words((4 << 16) | 71, target, decoration, value);
    }

    private static byte[] words(int... instructionWords) {
        ByteBuffer result = ByteBuffer.allocate((5 + instructionWords.length) * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(1).putInt(0);
        for (int word : instructionWords) result.putInt(word);
        return result.array();
    }
}
