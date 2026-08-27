package dev.comfyfluffy.caustica.api.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GpuContractTest {
    @Test
    void descriptorPropertiesRejectNonPowerOfTwoAlignment() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDescriptorHeapProperties(32, 32, 16, 3, 16, 16, 128));
    }
}
