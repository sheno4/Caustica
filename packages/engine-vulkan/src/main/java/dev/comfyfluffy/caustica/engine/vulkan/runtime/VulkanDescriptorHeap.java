package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeapProperties;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.engine.vulkan.descriptor.DescriptorHeapAllocationCore;
import dev.comfyfluffy.caustica.engine.vulkan.descriptor.DescriptorHeapBinding;
import dev.comfyfluffy.caustica.engine.vulkan.descriptor.DescriptorHeapKind;
import dev.comfyfluffy.caustica.engine.vulkan.descriptor.DescriptorHeapLayout;
import dev.comfyfluffy.caustica.engine.vulkan.descriptor.DescriptorHeapNativeWriter;
import dev.comfyfluffy.caustica.engine.vulkan.descriptor.DescriptorHeapStorage;
import dev.comfyfluffy.caustica.engine.vulkan.descriptor.DescriptorHeapWriterCore;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.EXTDescriptorHeap;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkBindHeapInfoEXT;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceAddressRangeEXT;
import org.lwjgl.vulkan.VkHostAddressRangeConstEXT;
import org.lwjgl.vulkan.VkHostAddressRangeEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceDescriptorHeapPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPushDataInfoEXT;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/** Owns the device-wide resource and sampler heaps bound by Caustica command streams. */
final class VulkanDescriptorHeap implements GpuDescriptorHeap, DescriptorHeapNativeWriter, AutoCloseable {
    private static final int TARGET_RESOURCE_CAPACITY = 262_144;
    private static final int TARGET_SAMPLER_CAPACITY = 16_384;
    private static final int MAX_RESOURCE_ALLOCATION = 65_536;
    private static final int MAX_SAMPLER_ALLOCATION = 4_096;

    private final VkDevice vk;
    private final GpuDescriptorHeapProperties properties;
    private final DescriptorHeapAllocationCore allocations;
    private final NativeStorage resources;
    private final NativeStorage samplers;
    private final DescriptorHeapBinding resourceBinding;
    private final DescriptorHeapBinding samplerBinding;
    private final DescriptorHeapWriterCore writer;
    private final long maxPushDataSize;

    static VulkanDescriptorHeap create(VkDevice vk, long vma, int graphicsQueueFamily, int computeQueueFamily) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceDescriptorHeapPropertiesEXT heapProperties =
                    VkPhysicalDeviceDescriptorHeapPropertiesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default().pNext(heapProperties);
            VK12.vkGetPhysicalDeviceProperties2(vk.getPhysicalDevice(), properties2);

            long resourceAlignment = max(heapProperties.imageDescriptorAlignment(),
                    heapProperties.bufferDescriptorAlignment());
            long resourceSize = max(heapProperties.imageDescriptorSize(), heapProperties.bufferDescriptorSize(),
                    EXTDescriptorHeap.vkGetPhysicalDeviceDescriptorSizeEXT(vk.getPhysicalDevice(),
                            KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR));
            long resourceStride = alignUp(resourceSize, resourceAlignment);
            long samplerStride = alignUp(heapProperties.samplerDescriptorSize(),
                    heapProperties.samplerDescriptorAlignment());
            int resourceCapacity = capacity(resourceStride, heapProperties.minResourceHeapReservedRange(),
                    heapProperties.maxResourceHeapSize(), TARGET_RESOURCE_CAPACITY, "resource");
            int samplerCapacity = capacity(samplerStride, heapProperties.minSamplerHeapReservedRange(),
                    heapProperties.maxSamplerHeapSize(), TARGET_SAMPLER_CAPACITY, "sampler");
            GpuDescriptorHeapProperties apiProperties = new GpuDescriptorHeapProperties(
                    resourceStride, samplerStride, resourceCapacity, samplerCapacity,
                    Math.min(resourceCapacity, MAX_RESOURCE_ALLOCATION),
                    Math.min(samplerCapacity, MAX_SAMPLER_ALLOCATION),
                    heapProperties.resourceHeapAlignment(), heapProperties.samplerHeapAlignment());
            DescriptorHeapAllocationCore allocations = new DescriptorHeapAllocationCore(apiProperties,
                    heapProperties.minResourceHeapReservedRange(), heapProperties.minSamplerHeapReservedRange(),
                    heapProperties.maxResourceHeapSize(), heapProperties.maxSamplerHeapSize());
            return new VulkanDescriptorHeap(vk, vma, apiProperties, allocations,
                    properties2.properties().limits().nonCoherentAtomSize(), heapProperties.maxPushDataSize(),
                    graphicsQueueFamily, computeQueueFamily);
        }
    }

    private VulkanDescriptorHeap(VkDevice vk, long vma, GpuDescriptorHeapProperties properties,
                                 DescriptorHeapAllocationCore allocations, long nonCoherentAtomSize,
                                 long maxPushDataSize, int graphicsQueueFamily, int computeQueueFamily) {
        this.vk = vk;
        this.properties = properties;
        this.allocations = allocations;
        NativeStorage resourceStorage = null;
        try {
            resourceStorage = NativeStorage.create(vk, vma, allocations.resources().layout(),
                    nonCoherentAtomSize, graphicsQueueFamily, computeQueueFamily);
            this.resources = resourceStorage;
            this.samplers = NativeStorage.create(vk, vma, allocations.samplers().layout(),
                    nonCoherentAtomSize, graphicsQueueFamily, computeQueueFamily);
        } catch (Throwable failure) {
            if (resourceStorage != null) resourceStorage.close();
            throw failure;
        }
        this.resourceBinding = new DescriptorHeapBinding(allocations.resources().layout(), resources);
        this.samplerBinding = new DescriptorHeapBinding(allocations.samplers().layout(), samplers);
        this.writer = new DescriptorHeapWriterCore(allocations, resources, samplers, this);
        this.maxPushDataSize = maxPushDataSize;
    }

    @Override
    public GpuDescriptorHeapProperties properties() {
        return properties;
    }

    @Override
    public GpuDescriptorRange<GpuDescriptorIndex.Resource> allocateResources(int descriptorCount, String label) {
        return allocations.allocateResources(descriptorCount);
    }

    @Override
    public GpuDescriptorRange<GpuDescriptorIndex.Sampler> allocateSamplers(int descriptorCount, String label) {
        return allocations.allocateSamplers(descriptorCount);
    }

    @Override
    public GpuDescriptorWriter writer() {
        return writer;
    }

    void bind(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            EXTDescriptorHeap.vkCmdBindResourceHeapEXT(commandBuffer, bindInfo(resourceBinding, stack));
            EXTDescriptorHeap.vkCmdBindSamplerHeapEXT(commandBuffer, bindInfo(samplerBinding, stack));
        }
    }

    void pushData(VkCommandBuffer commandBuffer, int offset, ByteBuffer data) {
        int size = data.remaining();
        if (offset < 0 || (offset & 3) != 0) {
            throw new IllegalArgumentException("push-data offset must be a non-negative multiple of four");
        }
        if (size == 0 || (size & 3) != 0) {
            throw new IllegalArgumentException("push-data size must be a positive multiple of four");
        }
        if (!data.isDirect()) throw new IllegalArgumentException("push data must be a direct buffer");
        if ((long) offset + size > maxPushDataSize) {
            throw new IllegalArgumentException("push data exceeds maxPushDataSize " + maxPushDataSize);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkHostAddressRangeConstEXT range = VkHostAddressRangeConstEXT.calloc(stack)
                    .address$(data);
            VkPushDataInfoEXT info = VkPushDataInfoEXT.calloc(stack).sType$Default()
                    .offset(offset).data(range);
            EXTDescriptorHeap.vkCmdPushDataEXT(commandBuffer, info);
        }
    }

    @Override
    public void writeSampler(long destinationHostAddress, VkSamplerCreateInfo sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkHostAddressRangeEXT.Buffer destination = destination(stack, destinationHostAddress,
                    properties.samplerDescriptorStride());
            VulkanDeviceContext.check(EXTDescriptorHeap.nvkWriteSamplerDescriptorsEXT(
                    vk, 1, sampler.address(), destination.address()), "vkWriteSamplerDescriptorsEXT");
        }
    }

    @Override
    public void writeResource(long destinationHostAddress, VkResourceDescriptorInfoEXT resource) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkHostAddressRangeEXT.Buffer destination = destination(stack, destinationHostAddress,
                    properties.resourceDescriptorStride());
            VulkanDeviceContext.check(EXTDescriptorHeap.nvkWriteResourceDescriptorsEXT(
                    vk, 1, resource.address(), destination.address()), "vkWriteResourceDescriptorsEXT");
        }
    }

    @Override
    public void writeAccelerationStructure(long destinationHostAddress, long accelerationStructure) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default()
                            .accelerationStructure(accelerationStructure);
            long deviceAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(vk, addressInfo);
            if (deviceAddress == 0L) throw new IllegalStateException("acceleration structure has a null device address");
            VkDeviceAddressRangeEXT range = VkDeviceAddressRangeEXT.calloc(stack)
                    .address$(deviceAddress).size(0L);
            VkResourceDescriptorInfoEXT resource = VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                    .type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .data(data -> data.pAddressRange(range));
            writeResource(destinationHostAddress, resource);
        }
    }

    @Override
    public void close() {
        samplers.close();
        resources.close();
    }

    private static VkBindHeapInfoEXT bindInfo(DescriptorHeapBinding binding, MemoryStack stack) {
        DescriptorHeapLayout layout = binding.layout();
        return VkBindHeapInfoEXT.calloc(stack).sType$Default()
                .heapRange(range -> range.address$(binding.storage().deviceAddress()).size(layout.heapSizeBytes()))
                .reservedRangeOffset(0L)
                .reservedRangeSize(layout.reservedRangeBytes());
    }

    private static VkHostAddressRangeEXT.Buffer destination(MemoryStack stack, long address, long size) {
        ByteBuffer bytes = MemoryUtil.memByteBuffer(address, Math.toIntExact(size));
        return VkHostAddressRangeEXT.calloc(1, stack).address$(bytes);
    }

    static long[] alignedFlushRange(long offset, long size, long allocationSize, long atomSize) {
        if (offset < 0 || size <= 0 || offset > allocationSize - size) {
            throw new IllegalArgumentException("flush range is outside the descriptor heap allocation");
        }
        long alignedOffset = offset - offset % atomSize;
        long end = Math.addExact(offset, size);
        long alignedEnd = Math.min(allocationSize, alignUp(end, atomSize));
        return new long[]{alignedOffset, alignedEnd - alignedOffset};
    }

    private static int capacity(long stride, long minimumReserved, long maximumHeapSize,
                                int target, String kind) {
        long reserved = alignUp(minimumReserved, stride);
        if (maximumHeapSize < reserved + stride) {
            throw new IllegalStateException(kind + " descriptor heap cannot fit an application descriptor");
        }
        long supported = (maximumHeapSize - reserved) / stride;
        return (int) Math.min(target, Math.min(supported, Integer.MAX_VALUE));
    }

    private static long alignUp(long value, long alignment) {
        long remainder = value % alignment;
        return remainder == 0 ? value : Math.addExact(value, alignment - remainder);
    }

    private static long max(long... values) {
        long maximum = 0L;
        for (long value : values) maximum = Math.max(maximum, value);
        return maximum;
    }

    private static final class NativeStorage implements DescriptorHeapStorage {
        private final long vma;
        private final DescriptorHeapKind kind;
        private final long buffer;
        private final long allocation;
        private final long deviceAddress;
        private final long mappedAddress;
        private final long sizeBytes;
        private final long allocationSize;
        private final long atomSize;

        private NativeStorage(long vma, DescriptorHeapKind kind, long buffer, long allocation,
                              long deviceAddress, long mappedAddress, long sizeBytes,
                              long allocationSize, long atomSize) {
            this.vma = vma;
            this.kind = kind;
            this.buffer = buffer;
            this.allocation = allocation;
            this.deviceAddress = deviceAddress;
            this.mappedAddress = mappedAddress;
            this.sizeBytes = sizeBytes;
            this.allocationSize = allocationSize;
            this.atomSize = atomSize;
        }

        static NativeStorage create(VkDevice vk, long vma, DescriptorHeapLayout layout, long atomSize,
                                    int graphicsQueueFamily, int computeQueueFamily) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                        .size(layout.heapSizeBytes())
                        .usage(VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                                | EXTDescriptorHeap.VK_BUFFER_USAGE_DESCRIPTOR_HEAP_BIT_EXT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
                if (graphicsQueueFamily != computeQueueFamily) {
                    bufferInfo.sharingMode(VK10.VK_SHARING_MODE_CONCURRENT)
                            .pQueueFamilyIndices(stack.ints(graphicsQueueFamily, computeQueueFamily));
                }
                VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                                | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                LongBuffer outBuffer = stack.mallocLong(1);
                PointerBuffer outAllocation = stack.mallocPointer(1);
                VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
                VulkanDeviceContext.check(Vma.vmaCreateBufferWithAlignment(vma, bufferInfo, allocationInfo,
                        layout.heapAddressAlignmentBytes(), outBuffer, outAllocation, info),
                        "vmaCreateBufferWithAlignment(" + layout.kind() + " descriptor heap)");
                long buffer = outBuffer.get(0);
                long allocation = outAllocation.get(0);
                try {
                    VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack)
                            .sType$Default().buffer(buffer);
                    long deviceAddress = VK12.vkGetBufferDeviceAddress(vk, addressInfo);
                    layout.validateStorage(deviceAddress, layout.heapSizeBytes());
                    if (info.pMappedData() == 0L) {
                        throw new IllegalStateException(layout.kind() + " descriptor heap is not mapped");
                    }
                    return new NativeStorage(vma, layout.kind(), buffer, allocation, deviceAddress,
                            info.pMappedData(), layout.heapSizeBytes(), info.size(), atomSize);
                } catch (Throwable failure) {
                    Vma.vmaDestroyBuffer(vma, buffer, allocation);
                    throw failure;
                }
            }
        }

        @Override public DescriptorHeapKind kind() { return kind; }
        @Override public long deviceAddress() { return deviceAddress; }
        @Override public long mappedAddress() { return mappedAddress; }
        @Override public long sizeBytes() { return sizeBytes; }

        @Override
        public void flush(long byteOffset, long byteSize) {
            if (byteOffset < 0 || byteSize <= 0 || byteOffset > sizeBytes - byteSize) {
                throw new IllegalArgumentException("flush range is outside the descriptor heap");
            }
            long[] range = alignedFlushRange(byteOffset, byteSize, allocationSize, atomSize);
            Vma.vmaFlushAllocation(vma, allocation, range[0], range[1]);
        }

        @Override
        public void close() {
            Vma.vmaDestroyBuffer(vma, buffer, allocation);
        }
    }
}
