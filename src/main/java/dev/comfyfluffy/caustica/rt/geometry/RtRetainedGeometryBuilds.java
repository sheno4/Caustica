package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

/** Owns retained packed-stream upload, BLAS construction, optional compaction, and unpublished lifetime. */
public final class RtRetainedGeometryBuilds {
    private RtRetainedGeometryBuilds() {
    }

    public static <M> Prepared<M> prepare(GpuContext ctx, RtPackedGeometry<M> packed,
                                          RtAccel.OpacityMicromapInput opacityInput,
                                          boolean compactBlas, long key,
                                          int originX, int originY, int originZ) {
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure
                .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        String label = "retained geometry " + key + " @ " + originX + "," + originY + "," + originZ;
        GpuBuffer positions = null;
        GpuBuffer indices = null;
        GpuBuffer textureCoordinates = null;
        GpuBuffer primitives = null;
        GpuBuffer upload = null;
        RtAccel.PreparedBlas blas = null;
        try {
            long positionsBytes = (long) packed.positions().length * Float.BYTES;
            long indicesBytes = (long) packed.indices().length * Integer.BYTES;
            long textureCoordinateBytes = (long) packed.textureCoordinates().length * Float.BYTES;
            long primitiveBytes = (long) packed.primitives().length * Float.BYTES;
            int transferDst = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

            positions = ctx.createAsyncBuffer(positionsBytes, asInput | transferDst, false, label + " positions");
            indices = ctx.createAsyncBuffer(indicesBytes, asInput | transferDst, false, label + " indices");
            textureCoordinates = ctx.createAsyncBuffer(textureCoordinateBytes, storage | transferDst,
                    false, label + " texture coordinates");
            primitives = ctx.createAsyncBuffer(primitiveBytes, storage | transferDst, false,
                    label + " primitives");
            upload = ctx.createUploadBuffer(positionsBytes + indicesBytes + textureCoordinateBytes + primitiveBytes,
                    label + " upload");

            long cursor = upload.mapped;
            MemoryUtil.memFloatBuffer(cursor, packed.positions().length).put(packed.positions());
            cursor += positionsBytes;
            MemoryUtil.memIntBuffer(cursor, packed.indices().length).put(packed.indices());
            cursor += indicesBytes;
            MemoryUtil.memFloatBuffer(cursor, packed.textureCoordinates().length).put(packed.textureCoordinates());
            cursor += textureCoordinateBytes;
            MemoryUtil.memFloatBuffer(cursor, packed.primitives().length).put(packed.primitives());
            upload.flush();

            blas = RtAccel.prepareRetainedBlas(ctx, positions, packed.vertexCount(), indices,
                    packed.classTriangles(), opacityInput, compactBlas, label + " BLAS");
            return new Prepared<>(key, positions, indices, textureCoordinates, primitives, upload, blas,
                    packed.triangleBases(), originX, originY, originZ, packed.metadata());
        } catch (Throwable failure) {
            if (blas != null) {
                destroy(new Prepared<>(key, positions, indices, textureCoordinates, primitives, upload, blas,
                        packed.triangleBases(), originX, originY, originZ, packed.metadata()));
            } else {
                if (upload != null) upload.destroy();
                if (primitives != null) primitives.destroy();
                if (textureCoordinates != null) textureCoordinates.destroy();
                if (indices != null) indices.destroy();
                if (positions != null) positions.destroy();
            }
            throw failure;
        }
    }

    /** Submit the upload/build and terminal compact-copy while preserving the executor's final build token. */
    public static <M> void submit(GpuContext ctx, Prepared<M> prepared, BooleanSupplier cancelled,
                                  Consumer<Completion<M>> completion) {
        ctx.gpuExecutor().submit(
                cancelled,
                cmd -> {
                    recordUpload(cmd, prepared);
                    RtAccel.recordBlasBuilds(ctx, cmd, List.of(prepared.blas()));
                },
                () -> {
                    RtAccel.freeBlasScratch(List.of(prepared.blas()));
                    prepared.releaseUpload();
                },
                (build, failure) -> {
                    if (failure != null || cancelled.getAsBoolean()) {
                        completion.accept(new Completion<>(prepared, build, failure));
                    } else if (prepared.blas().requestsCompaction()) {
                        submitCompaction(ctx, prepared, cancelled, build, completion);
                    } else {
                        prepared.releaseBuildInputs();
                        completion.accept(new Completion<>(prepared, build, null));
                    }
                });
    }

    private static <M> void submitCompaction(GpuContext ctx, Prepared<M> prepared,
                                             BooleanSupplier cancelled, RtGpuExecutor.Build build,
                                             Consumer<Completion<M>> completion) {
        RtAccel.PreparedBlasCompaction compaction;
        try {
            compaction = RtAccel.prepareBlasCompaction(ctx, prepared.blas());
        } catch (Throwable failure) {
            completion.accept(new Completion<>(prepared, build, failure));
            return;
        }
        try {
            ctx.gpuExecutor().submit(
                    cancelled,
                    cmd -> RtAccel.recordBlasCompaction(ctx, cmd, compaction),
                    () -> {
                        RtAccel.finishBlasCompaction(compaction);
                        prepared.releaseBuildInputs();
                    },
                    (copyBuild, failure) -> {
                        if (failure != null) {
                            Throwable terminal = failure;
                            try {
                                RtAccel.destroyBlasCompaction(compaction);
                            } catch (Throwable destroyFailure) {
                                terminal.addSuppressed(destroyFailure);
                            }
                            completion.accept(new Completion<>(prepared, copyBuild, terminal));
                        } else {
                            completion.accept(new Completion<>(prepared.withBlas(compaction.compacted()),
                                    copyBuild, null));
                        }
                    });
        } catch (Throwable failure) {
            try {
                RtAccel.destroyBlasCompaction(compaction);
            } catch (Throwable destroyFailure) {
                failure.addSuppressed(destroyFailure);
            }
            completion.accept(new Completion<>(prepared, build, failure));
        }
    }

    private static <M> void recordUpload(VkCommandBuffer cmd, Prepared<M> prepared) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            long sourceOffset = 0L;
            copy(cmd, prepared.upload(), prepared.positions(), sourceOffset, region);
            sourceOffset += prepared.positions().size;
            copy(cmd, prepared.upload(), prepared.indices(), sourceOffset, region);
            sourceOffset += prepared.indices().size;
            copy(cmd, prepared.upload(), prepared.textureCoordinates(), sourceOffset, region);
            sourceOffset += prepared.textureCoordinates().size;
            copy(cmd, prepared.upload(), prepared.primitives(), sourceOffset, region);

            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT)
                    .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                    .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default()
                    .pMemoryBarriers(barrier);
            vkCmdPipelineBarrier2KHR(cmd, dependency);
        }
    }

    private static void copy(VkCommandBuffer cmd, GpuBuffer upload, GpuBuffer destination,
                             long sourceOffset, VkBufferCopy.Buffer region) {
        region.get(0).srcOffset(sourceOffset).dstOffset(0L).size(destination.size);
        VK10.vkCmdCopyBuffer(cmd, upload.handle, destination.handle, region);
    }

    public static void destroy(Prepared<?> prepared) {
        RtAccel.freeBlasScratch(List.of(prepared.blas()));
        prepared.blas().accel.destroy();
        prepared.upload().destroy();
        prepared.primitives().destroy();
        prepared.textureCoordinates().destroy();
        prepared.indices().destroy();
        prepared.positions().destroy();
    }

    public record Completion<M>(Prepared<M> prepared, RtGpuExecutor.Build build, Throwable failure) {
    }

    public record Prepared<M>(long key, GpuBuffer positions, GpuBuffer indices,
                              GpuBuffer textureCoordinates, GpuBuffer primitives, GpuBuffer upload,
                              RtAccel.PreparedBlas blas, int[] triangleBases,
                              int originX, int originY, int originZ, M metadata) {
        void releaseUpload() {
            upload.destroy();
        }

        void releaseBuildInputs() {
            indices.destroy();
            positions.destroy();
        }

        Prepared<M> withBlas(RtAccel.PreparedBlas replacement) {
            return new Prepared<>(key, positions, indices, textureCoordinates, primitives, upload,
                    replacement, triangleBases, originX, originY, originZ, metadata);
        }
    }
}
