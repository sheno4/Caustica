package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

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
        OwnedBuffer positionsBuffer = create(resources, (long) positions.length * Float.BYTES,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true,
                bytes -> bytes.asFloatBuffer().put(positions));
        OwnedBuffer indexBuffer = null;
        OwnedBuffer primitiveBuffer = null;
        try {
            indexBuffer = create(resources, (long) indices.length * Integer.BYTES,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true,
                    bytes -> bytes.asIntBuffer().put(indices));
            primitiveBuffer = create(resources, (long) indices.length / 3L * PRIMITIVE_BYTES,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, bytes -> {
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
            try (var positionsOwner = positionsBuffer; var indexOwner = indexBuffer;
                 var primitiveOwner = primitiveBuffer) {
                throw failure;
            }
        }
    }

    private OwnedBuffer create(ResourceFactory resources, long size, int extraUsage,
                               boolean asyncShared, Consumer<ByteBuffer> writer) {
        VmaMappedBuffer buffer = asyncShared
                ? VmaMappedBuffer.createAsync(gpu, size, extraUsage, "glTF mesh data")
                : VmaMappedBuffer.create(gpu, size, extraUsage, "glTF mesh data");
        try {
            ByteBuffer bytes = buffer.mapped().order(ByteOrder.LITTLE_ENDIAN);
            writer.accept(bytes);
            buffer.flush(0L, size);
            return new OwnedBuffer(buffer, resources.create(buffer::close));
        } catch (RuntimeException | Error failure) {
            try (buffer) { throw failure; }
        }
    }

    private record UploadedPrimitive(OwnedBuffer positions, OwnedBuffer indices,
                                     OwnedBuffer primitiveData,
                                     int vertexCount, int indexCount) implements GltfPrimitiveUploader.Uploaded {
        @Override public MeshBuild.Stream positionsStream() {
            return new MeshBuild.Stream(positions.buffer.deviceRange(), 3 * Float.BYTES,
                    positions.owner);
        }
        @Override public MeshBuild.Stream indexStream() {
            return new MeshBuild.Stream(indices.buffer.deviceRange(), Integer.BYTES,
                    indices.owner);
        }
        @Override public VulkanDeviceAddress primitiveDataAddress() {
            return primitiveData.buffer.deviceRange().address();
        }
        @Override public ResourceOwner primitiveDataResource() {
            return primitiveData.owner;
        }
        @Override public void close() {
            try (positions; indices; primitiveData) { }
        }
    }

    private record OwnedBuffer(VmaMappedBuffer buffer, ResourceOwner owner) implements AutoCloseable {
        @Override public void close() { owner.close(); }
    }

}
