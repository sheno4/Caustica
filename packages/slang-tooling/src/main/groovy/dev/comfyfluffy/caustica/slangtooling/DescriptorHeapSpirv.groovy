package dev.comfyfluffy.caustica.slangtooling

import org.gradle.api.GradleException

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Validates the descriptor-heap instructions required by heap-native Vulkan shaders. */
final class DescriptorHeapSpirv {
    private static final int SPIRV_MAGIC = 0x07230203
    private static final int OP_DECORATE = 71
    private static final int DECORATION_BINDING = 33
    private static final int DECORATION_DESCRIPTOR_SET = 34

    static void validate(File file) {
        byte[] bytes = file.bytes
        if (bytes.length < 5 * Integer.BYTES || (bytes.length & 3) != 0) {
            throw new GradleException("${file} is not a complete SPIR-V module")
        }
        ByteBuffer words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (words.getInt(0) != SPIRV_MAGIC) {
            throw new GradleException("${file} has an invalid SPIR-V magic number")
        }

        int wordCount = bytes.length / Integer.BYTES
        for (int word = 5; word < wordCount;) {
            int instruction = words.getInt(word * Integer.BYTES)
            int instructionWords = instruction >>> 16
            int opcode = instruction & 0xffff
            if (instructionWords == 0 || instructionWords > wordCount - word) {
                throw new GradleException("${file} contains a malformed SPIR-V instruction")
            }
            if (opcode == OP_DECORATE && instructionWords >= 3) {
                int decoration = words.getInt((word + 2) * Integer.BYTES)
                if (decoration == DECORATION_BINDING || decoration == DECORATION_DESCRIPTOR_SET) {
                    throw new GradleException("${file} contains descriptor-set decorations")
                }
            }
            word += instructionWords
        }
    }

    private DescriptorHeapSpirv() { }
}
