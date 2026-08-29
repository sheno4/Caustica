package dev.comfyfluffy.caustica.vulkan;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class ShaderObjectGraphicsTest {
    @Test void descriptorSetDecorationsAreRejectedByTheSharedShaderObjectValidator() {
        ByteBuffer module = ByteBuffer.allocateDirect(8 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        module.putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(2).putInt(0);
        module.putInt((3 << 16) | 71).putInt(1).putInt(34).flip();
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectCompute.validateDescriptorHeapSpirv(module));
    }
}
