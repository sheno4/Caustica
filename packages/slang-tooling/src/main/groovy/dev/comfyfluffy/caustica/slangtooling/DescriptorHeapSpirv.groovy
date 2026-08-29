package dev.comfyfluffy.caustica.slangtooling

import org.gradle.api.GradleException

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Validates the descriptor-heap instructions required by heap-native Vulkan shaders. */
final class DescriptorHeapSpirv {
    private static final int SPIRV_MAGIC = 0x07230203
    private static final int OP_TYPE_POINTER = 32
    private static final int OP_VARIABLE = 59
    private static final int OP_DECORATE = 71
    private static final int OP_TYPE_ACCELERATION_STRUCTURE_KHR = 5341
    private static final int DECORATION_BINDING = 33
    private static final int DECORATION_DESCRIPTOR_SET = 34
    private static final int MAPPED_TLAS_SET = 0
    private static final int MAPPED_TLAS_BINDING = 0

    static void validate(File file, boolean mappedDescriptorBindings = false) {
        byte[] bytes = file.bytes
        if (bytes.length < 5 * Integer.BYTES || (bytes.length & 3) != 0) {
            throw new GradleException("${file} is not a complete SPIR-V module")
        }
        ByteBuffer words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (words.getInt(0) != SPIRV_MAGIC) {
            throw new GradleException("${file} has an invalid SPIR-V magic number")
        }

        int wordCount = bytes.length / Integer.BYTES
        Set<Integer> accelerationStructureTypes = []
        Map<Integer, Integer> pointerTargets = [:]
        Map<Integer, Integer> variableTypes = [:]
        Map<Integer, Integer> bindings = [:]
        Map<Integer, Integer> descriptorSets = [:]
        for (int word = 5; word < wordCount;) {
            int instruction = words.getInt(word * Integer.BYTES)
            int instructionWords = instruction >>> 16
            int opcode = instruction & 0xffff
            if (instructionWords == 0 || instructionWords > wordCount - word) {
                throw new GradleException("${file} contains a malformed SPIR-V instruction")
            }
            if (opcode == OP_TYPE_ACCELERATION_STRUCTURE_KHR && instructionWords >= 2) {
                accelerationStructureTypes.add(words.getInt((word + 1) * Integer.BYTES))
            } else if (opcode == OP_TYPE_POINTER && instructionWords >= 4) {
                pointerTargets[words.getInt((word + 1) * Integer.BYTES)] =
                        words.getInt((word + 3) * Integer.BYTES)
            } else if (opcode == OP_VARIABLE && instructionWords >= 4) {
                variableTypes[words.getInt((word + 2) * Integer.BYTES)] =
                        words.getInt((word + 1) * Integer.BYTES)
            } else if (opcode == OP_DECORATE && instructionWords >= 3) {
                int target = words.getInt((word + 1) * Integer.BYTES)
                int decoration = words.getInt((word + 2) * Integer.BYTES)
                if (decoration == DECORATION_BINDING || decoration == DECORATION_DESCRIPTOR_SET) {
                    if (!mappedDescriptorBindings) {
                        throw new GradleException("${file} contains descriptor-set decorations")
                    }
                    if (instructionWords < 4) {
                        throw new GradleException("${file} contains an incomplete descriptor decoration")
                    }
                    int value = words.getInt((word + 3) * Integer.BYTES)
                    if (decoration == DECORATION_BINDING) bindings[target] = value
                    else descriptorSets[target] = value
                }
            }
            word += instructionWords
        }
        if (mappedDescriptorBindings) {
            Set<Integer> decorated = (bindings.keySet() + descriptorSets.keySet()) as Set
            if (decorated.size() != 1) {
                throw new GradleException("${file} does not contain the expected mapped TLAS binding")
            }
            decorated.each { target ->
                Integer pointerType = variableTypes[target]
                if (bindings[target] != MAPPED_TLAS_BINDING || descriptorSets[target] != MAPPED_TLAS_SET
                        || pointerType == null || !accelerationStructureTypes.contains(pointerTargets[pointerType])) {
                    throw new GradleException("${file} contains a descriptor binding other than the mapped TLAS")
                }
            }
        }
    }

    private DescriptorHeapSpirv() { }
}
