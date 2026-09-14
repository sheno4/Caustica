package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.ShadowDiagnosticsData;
import dev.comfyfluffy.caustica.renderer.raytracing.resource.RtCompletionSlotPool;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteOrder;

/** Opt-in fill-pass counters read only after their owning graphics frame completes. */
final class RtShadowDiagnostics implements AutoCloseable {
    static final boolean ENABLED = Boolean.getBoolean("caustica.rt.shadowDiagnostics");
    private final RtCompletionSlotPool<Buffers> slots = new RtCompletionSlotPool<>(Buffers::close);

    Reservation begin(VulkanDeviceContext context, VkCommandBuffer command, MemoryStack stack,
                      GraphicsUse use, long frameId) {
        var buffers = slots.acquire(value -> true, () -> allocate(context));
        var reservation = new Reservation(buffers, frameId);
        use.keepAlive(reservation);
        VK10.vkCmdFillBuffer(command, buffers.gpu.handle(), 0, ShadowDiagnosticsData.BYTE_SIZE, 0);
        barrier(command, stack, buffers.gpu, VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT, KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        return reservation;
    }

    private static Buffers allocate(VulkanDeviceContext context) {
        var gpu = context.createBuffer(ShadowDiagnosticsData.BYTE_SIZE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                        | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, "shadow traversal counters");
        try {
            return new Buffers(gpu, context.createReadbackBuffer(ShadowDiagnosticsData.BYTE_SIZE,
                    "shadow traversal readback"));
        } catch (RuntimeException | Error failure) {
            gpu.destroy();
            throw failure;
        }
    }

    private static void barrier(VkCommandBuffer command, MemoryStack stack, GpuBuffer buffer,
                                long sourceStage, long sourceAccess, long destinationStage, long destinationAccess) {
        var barrier = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(sourceStage).srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage).dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer.handle()).offset(0).size(ShadowDiagnosticsData.BYTE_SIZE);
        VK13.vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default()
                .pBufferMemoryBarriers(barrier));
    }

    private record Buffers(GpuBuffer gpu, GpuBuffer readback) {
        void close() {
            gpu.destroy();
            readback.destroy();
        }
    }

    final class Reservation implements AutoCloseable {
        private final Buffers buffers;
        private final long frameId;
        private boolean submitted;

        Reservation(Buffers buffers, long frameId) {
            this.buffers = buffers;
            this.frameId = frameId;
        }

        VulkanDeviceAddress address() { return buffers.gpu.deviceAddress(); }

        void copy(VkCommandBuffer command, MemoryStack stack, GraphicsUse use) {
            barrier(command, stack, buffers.gpu, KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                    VK13.VK_ACCESS_2_TRANSFER_READ_BIT);
            var region = VkBufferCopy2.calloc(1, stack).sType$Default()
                    .srcOffset(0).dstOffset(0).size(ShadowDiagnosticsData.BYTE_SIZE);
            VK13.vkCmdCopyBuffer2(command, VkCopyBufferInfo2.calloc(stack).sType$Default()
                    .srcBuffer(buffers.gpu.handle()).dstBuffer(buffers.readback.handle()).pRegions(region));
            barrier(command, stack, buffers.readback, VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                    VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_HOST_BIT,
                    VK13.VK_ACCESS_2_HOST_READ_BIT);
            use.whenSubmitted(() -> submitted = true);
        }

        @Override public void close() {
            try {
                if (submitted) {
                    buffers.readback.invalidate();
                    var data = ShadowDiagnosticsData.read(MemoryUtil.memByteBuffer(buffers.readback.mapped(),
                            ShadowDiagnosticsData.BYTE_SIZE).order(ByteOrder.nativeOrder()));
                    publish(frameId, data);
                }
            } finally {
                slots.release(buffers);
            }
        }
    }

    static void publish(long frameId, ShadowDiagnosticsData data) {
        var event = new ShadowTraversalEvent();
        event.frameId = frameId;
        event.maxShadowRestartsAbove32 = Integer.toUnsignedLong(data.maxShadowRestartsAbove32());
        event.maxQueryProceedAbove256 = Integer.toUnsignedLong(data.maxQueryProceedAbove256());
        event.maxShadowProceedAbove512 = Integer.toUnsignedLong(data.maxShadowProceedAbove512());
        event.unchangedOriginEvents = Integer.toUnsignedLong(data.unchangedOriginEvents());
        event.repeatedAcceptedPrimitiveEvents = Integer.toUnsignedLong(data.repeatedAcceptedPrimitiveEvents());
        event.firstAnomalyPixelX = data.firstAnomalyPixelX();
        event.firstAnomalyPixelY = data.firstAnomalyPixelY();
        event.firstAnomalyGeometryRecord = data.firstAnomalyGeometryRecord();
        event.firstAnomalyPrimitive = data.firstAnomalyPrimitive();
        event.firstAnomalyRestarts = data.firstAnomalyRestarts();
        event.firstAnomalyProceedSum = data.firstAnomalyProceedSum();
        event.firstAnomalyFlags = data.firstAnomalyFlags();
        event.commit();
    }

    @Override public void close() { slots.close(); }

    @Name("dev.comfyfluffy.caustica.ShadowTraversal")
    @Label("Shadow traversal thresholds")
    @Category({"Caustica", "GPU"})
    @Description("Completed fill-pass counters; zero maxima mean no threshold exceedance. Frame ID identifies the source GPU frame.")
    @StackTrace(false)
    @Enabled(false)
    static final class ShadowTraversalEvent extends Event {
        long frameId;
        long maxShadowRestartsAbove32;
        long maxQueryProceedAbove256;
        long maxShadowProceedAbove512;
        long unchangedOriginEvents;
        long repeatedAcceptedPrimitiveEvents;
        int firstAnomalyPixelX;
        int firstAnomalyPixelY;
        int firstAnomalyGeometryRecord;
        int firstAnomalyPrimitive;
        int firstAnomalyRestarts;
        int firstAnomalyProceedSum;
        int firstAnomalyFlags;
    }
}
