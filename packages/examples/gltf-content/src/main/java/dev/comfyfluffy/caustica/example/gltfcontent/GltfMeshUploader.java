package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
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
    public GltfPrimitiveUploader.Uploaded upload(ResourceFactory resources, GltfScene.Primitive primitive) {
        float[] positions = primitive.positions();
        int[] indices = primitive.indices();
        OwnedBuffer positionsBuffer = createAsync(resources, (long) positions.length * Float.BYTES,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                bytes -> { for (float value : positions) bytes.putFloat(value); });
        OwnedBuffer indexBuffer = null;
        OwnedBuffer primitiveBuffer = null;
        try {
            indexBuffer = createAsync(resources, (long) indices.length * Integer.BYTES,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    bytes -> { for (int index : indices) bytes.putInt(index); });
            primitiveBuffer = create(resources, (long) indices.length / 3L * PRIMITIVE_BYTES,
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
            if (primitiveBuffer != null) primitiveBuffer.drop();
            if (indexBuffer != null) indexBuffer.drop();
            positionsBuffer.drop();
            throw failure;
        }
    }

    private OwnedBuffer create(ResourceFactory resources, long size, int extraUsage, Writer writer) {
        return create(resources, size, extraUsage, writer, false);
    }

    private OwnedBuffer createAsync(ResourceFactory resources, long size, int extraUsage, Writer writer) {
        return create(resources, size, extraUsage, writer, true);
    }

    private OwnedBuffer create(ResourceFactory resources, long size, int extraUsage, Writer writer,
                               boolean asyncShared) {
        VmaMappedBuffer buffer = asyncShared
                ? VmaMappedBuffer.createAsync(gpu, size, extraUsage, "glTF mesh data")
                : VmaMappedBuffer.create(gpu, size, extraUsage, "glTF mesh data");
        try {
            ByteBuffer bytes = buffer.mapped().order(ByteOrder.LITTLE_ENDIAN);
            writer.write(bytes);
            buffer.flush(0L, size);
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
        ResourceGeneration generation;
        try {
            generation = resources.create(buffer::close);
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
        try {
            generation.seal();
            return new OwnedBuffer(buffer, generation);
        } catch (RuntimeException | Error failure) {
            generation.drop();
            throw failure;
        }
    }

    private record UploadedPrimitive(OwnedBuffer positions, OwnedBuffer indices,
                                     OwnedBuffer primitiveData,
                                     int vertexCount, int indexCount) implements GltfPrimitiveUploader.Uploaded {
        @Override public MeshBuild.Stream positionsStream() {
            return new MeshBuild.Stream(positions.buffer.deviceRange(), 3 * Float.BYTES,
                    positions.generation.reference());
        }
        @Override public MeshBuild.Stream indexStream() {
            return new MeshBuild.Stream(indices.buffer.deviceRange(), Integer.BYTES,
                    indices.generation.reference());
        }
        @Override public VulkanDeviceAddress primitiveDataAddress() {
            return primitiveData.buffer.deviceRange().address();
        }
        @Override public ResourceRef primitiveDataResource() {
            return primitiveData.generation.reference();
        }
        @Override public void drop() {
            primitiveData.drop();
            indices.drop();
            positions.drop();
        }
    }

    private record OwnedBuffer(VmaMappedBuffer buffer, ResourceGeneration generation) {
        void drop() { generation.drop(); }
    }

    @FunctionalInterface private interface Writer { void write(ByteBuffer bytes); }
}
