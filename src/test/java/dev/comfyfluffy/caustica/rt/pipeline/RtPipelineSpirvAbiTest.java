package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtPipelineSpirvAbiTest {
    @Test
    void acceptsAHeapNativeModuleWithoutSetDecorations() {
        RtShaderCode shader = RtShaderCode.of("heap-native", words(1 << 16));
        assertDoesNotThrow(() -> RtPipeline.requireDescriptorHeapCompatible(shader));
    }

    @Test
    void rejectsDescriptorSetAndBindingDecorationsBeforePipelineCreation() {
        assertThrows(IllegalArgumentException.class, () -> RtPipeline.requireDescriptorHeapCompatible(
                RtShaderCode.of("set", decoration(34))));
        assertThrows(IllegalArgumentException.class, () -> RtPipeline.requireDescriptorHeapCompatible(
                RtShaderCode.of("binding", decoration(33))));
    }

    @Test
    void rejectsMalformedInstructionRanges() {
        assertThrows(IllegalArgumentException.class, () -> RtPipeline.requireDescriptorHeapCompatible(
                RtShaderCode.of("malformed", words((4 << 16) | 1))));
    }

    private static byte[] decoration(int decoration) {
        return words((3 << 16) | 71, 1, decoration);
    }

    private static byte[] words(int... instructionWords) {
        ByteBuffer result = ByteBuffer.allocate((5 + instructionWords.length) * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(1).putInt(0);
        for (int word : instructionWords) result.putInt(word);
        return result.array();
    }
}
