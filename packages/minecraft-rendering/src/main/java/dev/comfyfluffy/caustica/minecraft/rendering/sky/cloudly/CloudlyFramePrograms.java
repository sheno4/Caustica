package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Original frame programs share shader objects; their parameters retire with each recorded frame. */
public final class CloudlyFramePrograms implements AutoCloseable {
    private final Map<String, CloudlyShaderLibrary.Instance> programs = new LinkedHashMap<>();
    private final CloudlyFrameArena arena;

    public CloudlyFramePrograms(GpuDevice gpu, ResourceFactory resources, CloudlyShaderLibrary library,
                                Set<String> programIds) throws IOException {
        arena = new CloudlyFrameArena(gpu, resources);
        try {
            for (String id : programIds) programs.put(id, library.program(id).create(gpu, resources));
        } catch (IOException | RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, this::close);
            throw failure;
        }
    }

    /** All images and buffers reachable through the supplied heap table accompany this frame as explicit owners. */
    public void record(PassFrame frame, String programId, ByteBuffer constants,
                       Map<String, ? extends GpuDescriptorIndex> descriptors, Map<String, Long> sourcePush,
                       CloudlyFrameCamera camera, List<ResourceOwner> dependencies, int x, int y, int z) {
        recordDispatch(frame, programId, constants, descriptors, sourcePush, camera, dependencies,
                (shader, push) -> shader.dispatch(frame.commandBuffer(), push, x, y, z));
    }

    /** Original tile classification writes commands on the GPU; no host count readback is needed. */
    public void recordIndirect(PassFrame frame, String programId, ByteBuffer constants,
                               Map<String, ? extends GpuDescriptorIndex> descriptors, Map<String, Long> sourcePush,
                               CloudlyFrameCamera camera, List<ResourceOwner> dependencies,
                               long argumentBuffer, long argumentBytes, long argumentOffset, ResourceOwner argumentOwner) {
        frame.retain(argumentOwner);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK13.VK_ACCESS_2_SHADER_READ_BIT);
            VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
        recordDispatch(frame, programId, constants, descriptors, sourcePush, camera, dependencies,
                (shader, push) -> shader.shader().dispatchIndirect(frame.commandBuffer(), push,
                        argumentBuffer, argumentBytes, argumentOffset));
    }

    private void recordDispatch(PassFrame frame, String programId, ByteBuffer constants,
                                Map<String, ? extends GpuDescriptorIndex> descriptors, Map<String, Long> sourcePush,
                                CloudlyFrameCamera camera, List<ResourceOwner> dependencies,
                                BiConsumer<CloudlyShaderLibrary.Instance, ByteBuffer> dispatch) {
        var shader = programs.get(programId);
        var program = shader.program();
        if (constants.remaining() != program.parametersByteSize()) throw new IllegalArgumentException("Wrong original frame constants size");
        int bindingsOffset = program.parametersByteSize();
        int cameraOffset = (bindingsOffset + Math.max(4, program.bindingByteSize()) + 15) & ~15;
        boolean usesCamera = program.pushLayout().fields().stream().anyMatch(field -> field.name().equals("cameraAddress"));
        int size = cameraOffset + (usesCamera ? CloudlyFrameCamera.BYTE_SIZE : 0);
        try (var upload = arena.acquire(size); MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bytes = upload.bytes();
            bytes.slice(0, bindingsOffset).put(constants.duplicate());
            program.bindingsLayout().write(bytes.slice(bindingsOffset, program.bindingByteSize()), descriptors);
            if (usesCamera) camera.write(bytes.slice(cameraOffset, CloudlyFrameCamera.BYTE_SIZE));
            upload.flush();
            frame.retain(upload.owner());
            frame.retain(shader.owner());
            dependencies.forEach(frame::retain);
            Map<String, Long> push = new LinkedHashMap<>(sourcePush);
            push.put("parametersAddress", upload.addressAt(0));
            push.put("bindingsAddress", upload.addressAt(bindingsOffset));
            if (usesCamera) push.put("cameraAddress", upload.addressAt(cameraOffset));
            ByteBuffer payload = stack.malloc(program.pushLayout().byteSize()).order(ByteOrder.LITTLE_ENDIAN);
            program.pushLayout().write(payload, push);
            dispatch.accept(shader, payload);
            var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
            VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }

    @Override public void close() {
        new ResourceLifetime(arena::close,
                () -> new ResourceLifetime(programs.values().stream().<Runnable>map(program -> program::close)
                        .toArray(Runnable[]::new)).close()).close();
        programs.clear();
    }
}
