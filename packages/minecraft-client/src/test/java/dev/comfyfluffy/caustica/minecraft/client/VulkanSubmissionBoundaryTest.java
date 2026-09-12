package dev.comfyfluffy.caustica.minecraft.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the actual host bytecode that the submission Mixin wraps, without starting Minecraft. */
final class VulkanSubmissionBoundaryTest {
    private static final String ENCODER = "com/mojang/blaze3d/vulkan/VulkanCommandEncoder";

    @Test
    void hostSubmitHasOneAwaitBetweenPackingAndAllRetirementOperations() throws IOException {
        List<String> calls = calls("submit", "()V");
        System.out.println("Host submit bytecode calls: " + calls);
        String await = ENCODER + ".awaitSubmitCompletion(JJ)Z";
        assertEquals(1, calls.stream().filter(await::equals).count());
        int boundary = calls.indexOf(await);
        assertBefore(calls, boundary, ENCODER + ".endCommandBuffer()V");
        assertBefore(calls, boundary, ENCODER + ".signalSemaphore(JJJ)V");
        assertBefore(calls, boundary, "com/mojang/blaze3d/vulkan/VulkanQueue$Submission.close()V");
        assertBefore(calls, boundary, "com/mojang/blaze3d/vulkan/VulkanQueue.beginSubmit()Lcom/mojang/blaze3d/vulkan/VulkanQueue$Submission;");
        assertTrue(calls.subList(0, boundary).stream().anyMatch(v -> v.endsWith(".endSubmit()V")));
        assertTrue(calls.subList(boundary + 1, calls.size()).stream().anyMatch(v -> v.endsWith("VulkanCommandPool.reset()V")));
        assertTrue(calls.subList(boundary + 1, calls.size()).contains("com/mojang/blaze3d/vulkan/DestructionQueue.rotate()Z"));
        assertTrue(calls.subList(boundary + 1, calls.size()).contains("com/mojang/blaze3d/vulkan/checkpoints/CheckpointExtension$CheckpointStorage.rotate()V"));
        assertTrue(calls.subList(boundary + 1, calls.size()).stream().anyMatch(v -> v.endsWith(".beginSubmit()V")));
    }

    @Test
    void nativeCompletionWaitIsInsideTheExcludedHelper() throws IOException {
        var calls = calls("awaitSubmitCompletion", "(JJ)Z");
        assertEquals(1, calls.stream().filter(v -> v.contains(".vkWaitSemaphores(")).count());
        assertTrue(calls.stream().anyMatch(v -> v.contains("VulkanUtils.crashIfFailure(")));
        assertTrue(calls("submit", "()V").stream().noneMatch(v -> v.contains(".vkWaitSemaphores(")));
    }

    private static void assertBefore(List<String> calls, int boundary, String call) {
        int index = calls.indexOf(call);
        assertTrue(index >= 0 && index < boundary, () -> call + " not before await: " + calls);
    }

    private static List<String> calls(String method, String descriptor) throws IOException {
        var calls = new ArrayList<String>();
        try (var input = VulkanSubmissionBoundaryTest.class.getClassLoader().getResourceAsStream(ENCODER + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (!method.equals(name) || !descriptor.equals(desc)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            calls.add(owner + "." + name + desc);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertFalse(calls.isEmpty(), method);
        return calls;
    }
}
