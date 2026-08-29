package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** Uploads source-owned glTF acceleration inputs and shader primitive records through the public device. */
public final class GltfMeshUploader implements GltfPrimitiveUploader {
    private static final int PRIMITIVE_BYTES = 32;
    private final GpuDevice gpu;

    public GltfMeshUploader(GpuDevice gpu) { this.gpu = gpu; }

    @Override
    public GltfPrimitiveUploader.Uploaded upload(GltfScene.Primitive primitive) {
        float[] positions = primitive.positions();
        int[] indices = primitive.indices();
        VmaMappedBuffer positionsBuffer = create((long) positions.length * Float.BYTES,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                bytes -> { for (float value : positions) bytes.putFloat(value); });
        VmaMappedBuffer indexBuffer = null;
        VmaMappedBuffer primitiveBuffer = null;
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
            if (primitiveBuffer != null) primitiveBuffer.close();
            if (indexBuffer != null) indexBuffer.close();
            positionsBuffer.close();
            throw failure;
        }
    }

    private VmaMappedBuffer create(long size, int extraUsage, Writer writer) {
        VmaMappedBuffer buffer = VmaMappedBuffer.create(gpu, size, extraUsage, "glTF mesh data");
        try {
            ByteBuffer bytes = buffer.mapped().order(ByteOrder.LITTLE_ENDIAN);
            writer.write(bytes);
            buffer.flush(0L, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    private record UploadedPrimitive(VmaMappedBuffer positions, VmaMappedBuffer indices,
                                     VmaMappedBuffer primitiveData,
                                     int vertexCount, int indexCount) implements GltfPrimitiveUploader.Uploaded {
        @Override public MeshBuild.Stream positionsStream() {
            return new MeshBuild.Stream(positions.deviceRange(), 3 * Float.BYTES);
        }
        @Override public MeshBuild.Stream indexStream() {
            return new MeshBuild.Stream(indices.deviceRange(), Integer.BYTES);
        }
        @Override public VulkanDeviceAddress primitiveDataAddress() {
            return primitiveData.deviceRange().address();
        }
        @Override public void destroy() {
            primitiveData.close();
            indices.close();
            positions.close();
        }
    }

    @FunctionalInterface private interface Writer { void write(ByteBuffer bytes); }
}
