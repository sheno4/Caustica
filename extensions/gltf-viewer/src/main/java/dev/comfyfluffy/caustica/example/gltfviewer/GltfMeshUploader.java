package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.gpu.VulkanDeviceAddressRange;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

/** Uploads source-owned glTF acceleration inputs and shader primitive records through the public device. */
final class GltfMeshUploader implements GltfPrimitiveUploader {
    private static final int PRIMITIVE_BYTES = 32;
    private final GpuDevice gpu;

    GltfMeshUploader(GpuDevice gpu) { this.gpu = gpu; }

    @Override
    public UploadedPrimitive upload(GltfViewerScene.Primitive primitive) {
        float[] positions = primitive.positions();
        int[] indices = primitive.indices();
        Buffer positionsBuffer = create((long) positions.length * Float.BYTES,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                bytes -> { for (float value : positions) bytes.putFloat(value); });
        Buffer indexBuffer = null;
        Buffer primitiveBuffer = null;
        try {
            indexBuffer = create((long) indices.length * Integer.BYTES,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    bytes -> { for (int index : indices) bytes.putInt(index); });
            primitiveBuffer = create((long) indices.length / 3L * PRIMITIVE_BYTES,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, bytes -> {
                        for (int triangle = 0; triangle < indices.length / 3; triangle++) {
                            bytes.putFloat(primitive.red()).putFloat(primitive.green())
                                    .putFloat(primitive.blue()).putFloat(primitive.alpha())
                                    .putFloat(primitive.roughness()).putFloat(primitive.metallic())
                                    .putFloat(0.0f).putFloat(0.0f);
                        }
                    });
            return new UploadedPrimitive(positionsBuffer, indexBuffer, primitiveBuffer,
                    positions.length / 3, indices.length);
        } catch (RuntimeException | Error failure) {
            if (primitiveBuffer != null) primitiveBuffer.destroy();
            if (indexBuffer != null) indexBuffer.destroy();
            positionsBuffer.destroy();
            throw failure;
        }
    }

    private Buffer create(long size, int extraUsage, Writer writer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                    .usage(VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | extraUsage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer outBuffer = stack.mallocLong(1);
            PointerBuffer outAllocation = stack.mallocPointer(1);
            VmaAllocationInfo outInfo = VmaAllocationInfo.calloc(stack);
            int result = vmaCreateBuffer(gpu.vmaAllocator(), bufferInfo, allocationInfo,
                    outBuffer, outAllocation, outInfo);
            if (result != VK_SUCCESS) throw new IllegalStateException("vmaCreateBuffer failed: " + result);
            long buffer = outBuffer.get(0);
            long allocation = outAllocation.get(0);
            long address = vkGetBufferDeviceAddress(gpu.vk(),
                    VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
            if (address == 0L || outInfo.pMappedData() == 0L) {
                Vma.vmaDestroyBuffer(gpu.vmaAllocator(), buffer, allocation);
                throw new IllegalStateException("glTF buffer is not mapped and device-addressable");
            }
            ByteBuffer bytes = MemoryUtil.memByteBuffer(outInfo.pMappedData(), Math.toIntExact(size))
                    .order(ByteOrder.LITTLE_ENDIAN);
            writer.write(bytes);
            Vma.vmaFlushAllocation(gpu.vmaAllocator(), allocation, 0, size);
            return new Buffer(buffer, allocation, address, size);
        }
    }

    record UploadedPrimitive(Buffer positions, Buffer indices, Buffer primitiveData,
                             int vertexCount, int indexCount) implements GltfPrimitiveUploader.Uploaded {
        @Override public MeshBuild.Stream positionsStream() { return positions.stream(3 * Float.BYTES); }
        @Override public MeshBuild.Stream indexStream() { return indices.stream(Integer.BYTES); }
        @Override public long primitiveDataAddress() { return primitiveData.address; }
        @Override public void destroy() {
            primitiveData.destroy();
            indices.destroy();
            positions.destroy();
        }
    }

    final class Buffer {
        private final long handle;
        private final long allocation;
        private final long address;
        private final long size;
        private boolean destroyed;

        private Buffer(long handle, long allocation, long address, long size) {
            this.handle = handle;
            this.allocation = allocation;
            this.address = address;
            this.size = size;
        }

        MeshBuild.Stream stream(int stride) {
            return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), size), stride);
        }

        void destroy() {
            if (destroyed) return;
            Vma.vmaDestroyBuffer(gpu.vmaAllocator(), handle, allocation);
            destroyed = true;
        }
    }

    @FunctionalInterface private interface Writer { void write(ByteBuffer bytes); }
}
