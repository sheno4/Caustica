package dev.comfyfluffy.caustica.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.EXTDescriptorHeap;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ShaderObjectGraphicsTest {
    @Test void vertexInputRetainsCallerSuppliedVulkanLayout() {
        var bindings = new ArrayList<>(List.of(new ShaderObjectGraphics.VertexBinding(
                3, 40, VK10.VK_VERTEX_INPUT_RATE_INSTANCE, 2)));
        var attributes = new ArrayList<>(List.of(new ShaderObjectGraphics.VertexAttribute(
                7, 3, VK10.VK_FORMAT_R16G16_SFLOAT, 12)));

        var input = new ShaderObjectGraphics.VertexInput(bindings, attributes);
        bindings.clear();
        attributes.clear();

        assertEquals(3, input.bindings().getFirst().binding());
        assertEquals(40, input.bindings().getFirst().stride());
        assertEquals(VK10.VK_VERTEX_INPUT_RATE_INSTANCE, input.bindings().getFirst().inputRate());
        assertEquals(2, input.bindings().getFirst().divisor());
        assertEquals(VK10.VK_FORMAT_R16G16_SFLOAT, input.attributes().getFirst().format());
    }

    @Test void blendContractRetainsCompleteVulkanEquation() {
        int mask = VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT;

        var alpha = new ShaderObjectGraphics.ColorBlend(true, VK10.VK_BLEND_FACTOR_SRC_ALPHA,
                VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK10.VK_BLEND_OP_MAX,
                VK10.VK_BLEND_FACTOR_SRC_ALPHA, VK10.VK_BLEND_FACTOR_DST_ALPHA,
                VK10.VK_BLEND_OP_MIN, mask);

        assertTrue(alpha.enabled());
        assertEquals(VK10.VK_BLEND_FACTOR_SRC_ALPHA, alpha.srcColorFactor());
        assertEquals(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, alpha.dstColorFactor());
        assertEquals(VK10.VK_BLEND_OP_MAX, alpha.colorOp());
        assertEquals(VK10.VK_BLEND_OP_MIN, alpha.alphaOp());
        assertEquals(mask, alpha.writeMask());
    }

    @Test void descriptorSetDecorationsAreRejectedByTheSharedShaderObjectValidator() {
        ByteBuffer module = descriptorBinding(1, 0, 0);
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectCompute.validateDescriptorHeapSpirv(module));
    }

    @Test void mappedValidatorRejectsOverlappingCoverageForOneResourceType() {
        var mapping = ShaderObjectGraphics.PushIndexedResourceMapping.accelerationStructure(0, 0, 96);
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectGraphics.validateMappedDescriptorHeapSpirv(
                        descriptorBinding(7, 0, 0), List.of(mapping, mapping)));
    }

    @Test void mappedValidatorRequiresEveryStaticDescriptorBindingToBeCovered() {
        ByteBuffer module = descriptorBinding(7, 0, 0);
        var mapping = ShaderObjectGraphics.PushIndexedResourceMapping.accelerationStructure(0, 0, 96);
        assertDoesNotThrow(() -> ShaderObjectGraphics.validateMappedDescriptorHeapSpirv(
                module, List.of(mapping)));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectGraphics.validateMappedDescriptorHeapSpirv(module,
                        List.of(ShaderObjectGraphics.PushIndexedResourceMapping.accelerationStructure(0, 1, 96))));
    }

    @Test void mappedValidatorAllowsDisjointResourceTypesAtTheSameBinding() {
        var acceleration = ShaderObjectGraphics.PushIndexedResourceMapping.accelerationStructure(0, 0, 96);
        var image = new ShaderObjectGraphics.PushIndexedResourceMapping(0, 0,
                EXTDescriptorHeap.VK_SPIRV_RESOURCE_TYPE_SAMPLED_IMAGE_BIT_EXT, 100);
        assertDoesNotThrow(() -> ShaderObjectGraphics.validateMappedDescriptorHeapSpirv(
                descriptorBinding(7, 0, 0), List.of(acceleration, image)));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectGraphics.validateMappedDescriptorHeapSpirv(
                        descriptorBinding(7, 0, 0), List.of(acceleration, image, image)));
    }

    @Test void accelerationStructureMappingRetainsItsStaticBindingAndPushOffset() {
        var mapping = ShaderObjectGraphics.PushIndexedResourceMapping.accelerationStructure(0, 0, 96);
        assertEquals(0, mapping.descriptorSet());
        assertEquals(0, mapping.binding());
        assertEquals(96, mapping.pushDataOffset());
        assertEquals(EXTDescriptorHeap.VK_SPIRV_RESOURCE_TYPE_ACCELERATION_STRUCTURE_BIT_EXT,
                mapping.resourceMask());
    }

    private static ByteBuffer descriptorBinding(int target, int descriptorSet, int binding) {
        ByteBuffer result = ByteBuffer.allocateDirect(13 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(8).putInt(0);
        result.putInt((4 << 16) | 71).putInt(target).putInt(34).putInt(descriptorSet);
        result.putInt((4 << 16) | 71).putInt(target).putInt(33).putInt(binding);
        return result.flip();
    }
}
