package dev.comfyfluffy.caustica.slangtooling;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DescriptorHeapSpirvTest {
    @TempDir Path temporary;

    @Test
    void acceptsOnlySetZeroBindingZeroAccelerationStructure() throws IOException {
        assertDoesNotThrow(() -> DescriptorHeapSpirv.validate(module(0, 0, true), true));
        assertThrows(GradleException.class, () -> DescriptorHeapSpirv.validate(module(1, 0, true), true));
        assertThrows(GradleException.class, () -> DescriptorHeapSpirv.validate(module(0, 1, true), true));
        assertThrows(GradleException.class, () -> DescriptorHeapSpirv.validate(module(0, 0, false), true));
    }

    @Test
    void rejectsTheMappedBindingUnlessTheSourceOptedIn() throws IOException {
        assertThrows(GradleException.class, () -> DescriptorHeapSpirv.validate(module(0, 0, true)));
    }

    private java.io.File module(int descriptorSet, int binding, boolean accelerationStructure) throws IOException {
        int pointeeType = 3;
        int[] instructions = accelerationStructure
                ? new int[]{(2 << 16) | 5341, pointeeType}
                : new int[]{(3 << 16) | 22, pointeeType, 32};
        ByteBuffer bytes = ByteBuffer.allocate((5 + instructions.length + 16) * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(8).putInt(0);
        for (int word : instructions) bytes.putInt(word);
        bytes.putInt((4 << 16) | 32).putInt(4).putInt(0).putInt(pointeeType);
        bytes.putInt((4 << 16) | 59).putInt(4).putInt(5).putInt(0);
        bytes.putInt((4 << 16) | 71).putInt(5).putInt(34).putInt(descriptorSet);
        bytes.putInt((4 << 16) | 71).putInt(5).putInt(33).putInt(binding);
        Path file = Files.createTempFile(temporary, "descriptor-heap", ".spv");
        Files.write(file, bytes.array());
        return file.toFile();
    }
}
