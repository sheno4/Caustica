package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedGeometryPlan;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;

import static dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext.check;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;

/** Descriptor-heap-native world ray-tracing pipeline and shader binding table. */
public final class RtPipeline {
    private final VulkanDeviceContext context;
    private final long pipeline;
    private final SbtBuffer sbt;
    private final long stride;
    private final int handleSize;
    private final int raygenCount;
    private final int missCount;
    private final int hitCount;
    private boolean destroyed;

    private RtPipeline(VulkanDeviceContext context, long pipeline, SbtBuffer sbt, long stride, int handleSize,
                       int raygenCount, int missCount, int hitCount) {
        this.context = context;
        this.pipeline = pipeline;
        this.sbt = sbt;
        this.stride = stride;
        this.handleSize = handleSize;
        this.raygenCount = raygenCount;
        this.missCount = missCount;
        this.hitCount = hitCount;
    }

    /** Creates a KHR ray-tracing pipeline whose shaders directly address the two descriptor heaps. */
    public static RtPipeline create(VulkanDeviceContext context, RtShaderCode[] raygen, RtShaderCode[] miss,
                                    RtShaderCode closestHit, RtShaderCode radianceAnyHit,
                                    RtShaderCode shadowAnyHit) {
        if (raygen.length == 0 || miss.length == 0) throw new IllegalArgumentException("empty RT stage array");
        boolean anyHit = radianceAnyHit != null || shadowAnyHit != null;
        if (anyHit && (radianceAnyHit == null || shadowAnyHit == null)) {
            throw new IllegalArgumentException("both any-hit shaders are required");
        }
        for (RtShaderCode shader : raygen) requireDescriptorHeapCompatible(shader);
        for (RtShaderCode shader : miss) requireDescriptorHeapCompatible(shader);
        requireDescriptorHeapCompatible(closestHit);
        if (anyHit) {
            requireDescriptorHeapCompatible(radianceAnyHit);
            requireDescriptorHeapCompatible(shadowAnyHit);
        }
        VkDevice device = context.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int raygenCount = raygen.length;
            int missCount = miss.length;
            int hitCount = anyHit ? RtAccel.SBT_HIT_GROUP_COUNT : 1;
            int closestStage = raygenCount + missCount;
            int radianceStage = closestStage + 1;
            int shadowStage = closestStage + 2;
            int stageCount = closestStage + 1 + (anyHit ? 2 : 0);
            int groupCount = raygenCount + missCount + hitCount;
            long[] modules = new long[stageCount];
            try {
                for (int i = 0; i < raygenCount; i++) modules[i] = module(device, stack, raygen[i]);
                for (int i = 0; i < missCount; i++) modules[raygenCount + i] = module(device, stack, miss[i]);
                modules[closestStage] = module(device, stack, closestHit);
                if (anyHit) {
                    modules[radianceStage] = module(device, stack, radianceAnyHit);
                    modules[shadowStage] = module(device, stack, shadowAnyHit);
                }

                ByteBuffer entry = stack.UTF8("main");
                VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
                for (int i = 0; i < raygenCount; i++) stage(stages.get(i), VK_SHADER_STAGE_RAYGEN_BIT_KHR, modules[i], entry);
                for (int i = 0; i < missCount; i++) stage(stages.get(raygenCount + i), VK_SHADER_STAGE_MISS_BIT_KHR,
                        modules[raygenCount + i], entry);
                stage(stages.get(closestStage), VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, modules[closestStage], entry);
                if (anyHit) {
                    stage(stages.get(radianceStage), VK_SHADER_STAGE_ANY_HIT_BIT_KHR, modules[radianceStage], entry);
                    stage(stages.get(shadowStage), VK_SHADER_STAGE_ANY_HIT_BIT_KHR, modules[shadowStage], entry);
                }

                VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount, stack);
                for (int i = 0; i < raygenCount + missCount; i++) {
                    groups.get(i).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                            .generalShader(i).closestHitShader(VK_SHADER_UNUSED_KHR)
                            .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
                }
                int firstHit = raygenCount + missCount;
                for (int i = 0; i < hitCount; i++) {
                    groups.get(firstHit + i).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                            .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(closestStage)
                            .anyHitShader(anyHitStage(anyHit, i, radianceStage, shadowStage))
                            .intersectionShader(VK_SHADER_UNUSED_KHR);
                }

                long flags = EXTDescriptorHeap.VK_PIPELINE_CREATE_2_DESCRIPTOR_HEAP_BIT_EXT;
                if (context.backend().capabilities().opacityMicromaps()) {
                    flags |= Integer.toUnsignedLong(VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT);
                }
                VkPipelineCreateFlags2CreateInfo flags2 = VkPipelineCreateFlags2CreateInfo.calloc(stack)
                        .sType$Default().flags(flags);
                VkRayTracingPipelineCreateInfoKHR.Buffer info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
                info.get(0).sType$Default().pNext(flags2.address()).pStages(stages).pGroups(groups)
                        .maxPipelineRayRecursionDepth(1).layout(VK10.VK_NULL_HANDLE);
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateRayTracingPipelinesKHR(device, VK10.VK_NULL_HANDLE, VK10.VK_NULL_HANDLE,
                        info, null, out), "vkCreateRayTracingPipelinesKHR");
                long pipeline = out.get(0);
                RtDebugLabels.name(context, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "world RT pipeline");
                SbtBuffer sbt = null;
                try {
                    int handleSize = context.shaderGroupHandleSize();
                    ByteBuffer handles = stack.malloc(groupCount * handleSize);
                    check(vkGetRayTracingShaderGroupHandlesKHR(device, pipeline, 0, groupCount, handles),
                            "vkGetRayTracingShaderGroupHandlesKHR");
                    long stride = align(handleSize, Math.max(context.shaderGroupBaseAlignment(),
                            context.shaderGroupHandleAlignment()));
                    if (stride > Integer.toUnsignedLong(context.maxShaderGroupStride())) {
                        throw new UnsupportedOperationException("SBT stride exceeds maxShaderGroupStride");
                    }
                    sbt = SbtBuffer.create(context, stride * groupCount, context.shaderGroupBaseAlignment());
                    for (int i = 0; i < groupCount; i++) {
                        MemoryUtil.memCopy(MemoryUtil.memAddress(handles) + (long) i * handleSize,
                                sbt.mapped + i * stride, handleSize);
                    }
                    Vma.vmaFlushAllocation(context.vmaAllocator(), sbt.allocation, 0, VK10.VK_WHOLE_SIZE);
                    return new RtPipeline(context, pipeline, sbt, stride, handleSize,
                            raygenCount, missCount, hitCount);
                } catch (RuntimeException | Error failure) {
                    if (sbt != null) sbt.destroy(context.vmaAllocator());
                    VK10.vkDestroyPipeline(device, pipeline, null);
                    throw failure;
                }
            } finally {
                for (long module : modules) if (module != 0L) VK10.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    /** Binds both heaps, publishes the complete world root with push data, and dispatches rays. */
    public void trace(VkCommandBuffer commandBuffer, int width, int height, ByteBuffer roots, int raygenIndex) {
        trace(commandBuffer, width, height, roots, raygenIndex, null);
    }

    /** Dispatches with a scene-specific hit table while retaining the pipeline-owned raygen and miss tables. */
    public void trace(VkCommandBuffer commandBuffer, int width, int height, ByteBuffer roots,
                      int raygenIndex, HitTable retainedHits) {
        if (destroyed) throw new IllegalStateException("pipeline is destroyed");
        if (raygenIndex < 0 || raygenIndex >= raygenCount) throw new IllegalArgumentException("raygen index out of range");
        if (roots.remaining() != RtBindings.WORLD_PUSH_CONSTANT_SIZE) {
            throw new IllegalArgumentException("world binding root must be exactly "
                    + RtBindings.WORLD_PUSH_CONSTANT_SIZE + " bytes");
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, commandBuffer, "trace rays")) {
            context.bindDescriptorHeaps(commandBuffer);
            context.pushData(commandBuffer, 0, roots);
            VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            VkStridedDeviceAddressRegionKHR rgen = region(stack,
                    sbt.address.addBytes((long) raygenIndex * stride), stride, stride);
            VkStridedDeviceAddressRegionKHR rmiss = region(stack,
                    sbt.address.addBytes((long) raygenCount * stride), stride, (long) missCount * stride);
            VkStridedDeviceAddressRegionKHR hit = retainedHits == null
                    ? region(stack, sbt.address.addBytes((long) (raygenCount + missCount) * stride),
                            stride, (long) hitCount * stride)
                    : region(stack, retainedHits.bytes().address(), retainedHits.stride(),
                            retainedHits.bytes().byteSize());
            vkCmdTraceRaysKHR(commandBuffer, rgen, rmiss, hit,
                    VkStridedDeviceAddressRegionKHR.calloc(stack), width, height, 1);
        }
    }

    public int retainedHitRecordStride() { return Math.toIntExact(stride); }

    public long retainedHitTableAlignment() { return context.shaderGroupBaseAlignment(); }

    /** Addressable scene-specific hit SBT region. Its owner retains the buffer through the trace use. */
    public record HitTable(VulkanDeviceAddressRange bytes, long stride) {
        public HitTable {
            if (stride <= 0L || bytes.byteSize() % stride != 0L) {
                throw new IllegalArgumentException("invalid retained hit table region");
            }
        }
    }

    public void destroy() {
        if (destroyed) return;
        sbt.destroy(context.vmaAllocator());
        VK10.vkDestroyPipeline(context.vk(), pipeline, null);
        destroyed = true;
    }

    /** CPU image for a scene-specific hit table; the caller owns uploading and retiring its SBT buffer. */
    public ByteBuffer retainedHitRecords(List<RtRetainedGeometryPlan.HitGroup> groups) {
        if (destroyed) throw new IllegalStateException("pipeline is destroyed");
        if (hitCount != RtAccel.SBT_HIT_GROUP_COUNT) {
            throw new IllegalStateException("pipeline has no coverage-class hit groups");
        }
        ByteBuffer handles = ByteBuffer.allocate(hitCount * handleSize);
        long firstHit = sbt.mapped + (long) (raygenCount + missCount) * stride;
        for (int group = 0; group < hitCount; group++) {
            for (int byteIndex = 0; byteIndex < handleSize; byteIndex++) {
                handles.put(group * handleSize + byteIndex,
                        MemoryUtil.memGetByte(firstHit + group * stride + byteIndex));
            }
        }
        return packRetainedHitRecords(handles, handleSize, Math.toIntExact(stride), groups);
    }

    static ByteBuffer packRetainedHitRecords(ByteBuffer fixedHitHandles, int handleSize, int recordStride,
                                             List<RtRetainedGeometryPlan.HitGroup> groups) {
        if (recordStride < handleSize) throw new IllegalArgumentException("record stride is smaller than a handle");
        ByteBuffer packed = ByteBuffer.allocate(Math.multiplyExact(recordStride, groups.size()));
        for (int record = 0; record < groups.size(); record++) {
            int source = fixedHitGroupIndex(groups.get(record)) * handleSize;
            int target = record * recordStride;
            for (int byteIndex = 0; byteIndex < handleSize; byteIndex++) {
                packed.put(target + byteIndex, fixedHitHandles.get(source + byteIndex));
            }
        }
        return packed;
    }

    static int fixedHitGroupIndex(RtRetainedGeometryPlan.HitGroup group) {
        return switch (group) {
            case RADIANCE_OPAQUE -> RtAccel.SBT_RADIANCE_OFFSET + RtAccel.CLASS_OPAQUE;
            case RADIANCE_CUTOUT -> RtAccel.SBT_RADIANCE_OFFSET + RtAccel.CLASS_MASKED;
            case SHADOW_OPAQUE -> RtAccel.SBT_SHADOW_OFFSET + RtAccel.CLASS_OPAQUE;
            case SHADOW_CUTOUT -> RtAccel.SBT_SHADOW_OFFSET + RtAccel.CLASS_MASKED;
        };
    }

    static long align(long value, long alignment) {
        if (value < 0 || alignment <= 0) throw new IllegalArgumentException("invalid alignment input");
        long remainder = value % alignment;
        return remainder == 0 ? value : Math.addExact(value, alignment - remainder);
    }

    static void requireDescriptorHeapCompatible(RtShaderCode shader) {
        byte[] code = shader.spirv();
        if (code.length < 5 * Integer.BYTES || (code.length & 3) != 0) {
            throw new IllegalArgumentException(shader.debugName() + " is not a complete SPIR-V module");
        }
        ByteBuffer words = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN);
        if (words.getInt(0) != 0x07230203) {
            throw new IllegalArgumentException(shader.debugName() + " has an invalid SPIR-V magic number");
        }
        int word = 5;
        int wordCount = code.length / Integer.BYTES;
        while (word < wordCount) {
            int instruction = words.getInt(word * Integer.BYTES);
            int instructionWords = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (instructionWords == 0 || instructionWords > wordCount - word) {
                throw new IllegalArgumentException(shader.debugName() + " has a malformed SPIR-V instruction");
            }
            if (opcode == 71 && instructionWords >= 3) { // OpDecorate
                int decoration = words.getInt((word + 2) * Integer.BYTES);
                if (decoration == 33 || decoration == 34) { // Binding, DescriptorSet
                    throw new IllegalArgumentException(shader.debugName()
                            + " contains descriptor-set decorations; heap-native RT stages require direct heap access");
                }
            }
            word += instructionWords;
        }
    }

    private static VkStridedDeviceAddressRegionKHR region(MemoryStack stack, VulkanDeviceAddress address,
                                                           long stride, long size) {
        return VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(address.value()).stride(stride).size(size);
    }

    private static void stage(VkPipelineShaderStageCreateInfo info, int stage, long module, ByteBuffer entry) {
        info.sType$Default().stage(stage).module(module).pName(entry);
    }

    private static int anyHitStage(boolean enabled, int hitGroup, int radiance, int shadow) {
        if (!enabled) return VK_SHADER_UNUSED_KHR;
        int rayType = hitGroup / RtAccel.SBT_CLASSES;
        int geometryClass = hitGroup % RtAccel.SBT_CLASSES;
        boolean used = rayType == RtAccel.SBT_RAY_RADIANCE
                ? geometryClass == RtAccel.CLASS_MASKED
                : geometryClass != RtAccel.CLASS_OPAQUE;
        if (!used) return VK_SHADER_UNUSED_KHR;
        return rayType == RtAccel.SBT_RAY_RADIANCE ? radiance : shadow;
    }

    private static long module(VkDevice device, MemoryStack stack, RtShaderCode shader) {
        ByteBuffer code = MemoryUtil.memAlloc(shader.spirv().length).put(shader.spirv()).flip();
        try {
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(device,
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, out),
                    "vkCreateShaderModule(" + shader.debugName() + ')');
            return out.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static final class SbtBuffer {
        final long buffer;
        final long allocation;
        final VulkanDeviceAddress address;
        final long mapped;

        private SbtBuffer(long buffer, long allocation, VulkanDeviceAddress address, long mapped) {
            this.buffer = buffer;
            this.allocation = allocation;
            this.address = address;
            this.mapped = mapped;
        }

        static SbtBuffer create(VulkanDeviceContext context, long size, long alignment) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                        .usage(VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                                | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                LongBuffer outBuffer = stack.mallocLong(1);
                PointerBuffer outAllocation = stack.mallocPointer(1);
                VmaAllocationInfo allocation = VmaAllocationInfo.calloc(stack);
                check(Vma.vmaCreateBufferWithAlignment(context.vmaAllocator(), bufferInfo, allocationInfo,
                        alignment, outBuffer, outAllocation, allocation), "vmaCreateBufferWithAlignment(SBT)");
                long buffer = outBuffer.get(0);
                long address = VK12.vkGetBufferDeviceAddress(context.vk(),
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
                if (address == 0L || allocation.pMappedData() == 0L) {
                    Vma.vmaDestroyBuffer(context.vmaAllocator(), buffer, outAllocation.get(0));
                    throw new IllegalStateException("SBT buffer is not addressable and mapped");
                }
                return new SbtBuffer(buffer, outAllocation.get(0), new VulkanDeviceAddress(address),
                        allocation.pMappedData());
            }
        }

        void destroy(long vma) { Vma.vmaDestroyBuffer(vma, buffer, allocation); }
    }
}
