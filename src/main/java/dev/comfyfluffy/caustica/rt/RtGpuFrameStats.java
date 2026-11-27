package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.compat.VivecraftCompat.View;
import java.io.PrintWriter;
import java.nio.LongBuffer;
import java.util.Arrays;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/** GPU timestamps for the composite command buffer, read only after its graphics token completes. */
final class RtGpuFrameStats {
    enum Stage {
        ENTITY_BLAS("entityBlas"), TLAS("tlas"), SKY_LUT("skyLut"),
        WIDE_PRIMARY("widePrimary"), WIDE_INDIRECT("wideIndirect"), WIDE_RR("wideRr"), WIDE_UPSCALE("wideUpscale"),
        INSET_PRIMARY("insetPrimary"), INSET_INDIRECT("insetIndirect"), INSET_RR("insetRr"), INSET_UPSCALE("insetUpscale"),
        MERGE("merge"), EXPOSURE("exposure"), BLOOM("bloom"), DISPLAY("display"), COPY_OUTPUT("copyOutput");

        private final String column;

        Stage(String column) {
            this.column = column;
        }
    }

    record FrameInfo(long frame, View view, int outputWidth, int outputHeight, int wideWidth, int wideHeight,
                     int insetWidth, int insetHeight, int coverageDegrees, boolean reusedInset, String gazeState) {}

    private static final Stage[] STAGES = Stage.values();
    private static final int QUERIES_PER_SAMPLE = STAGES.length * 2;
    private final Sample[] samples = new Sample[12];
    private final int[] stageStarts = new int[STAGES.length];
    private long pool;
    private VkDevice device;
    private long timestampMask;
    private double millisecondsPerTick;
    private PrintWriter csv;
    private boolean initialized;

    Sample begin(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack, RtGpuExecutor.GraphicsUseWaiter waiter,
                 FrameInfo info) {
        if (!initialized) {
            initialize(ctx, stack);
        }
        if (csv == null) {
            return Sample.DISABLED;
        }
        Sample available = null;
        for (Sample sample : samples) {
            if (sample.use != null && waiter.completed(sample.use)) {
                writeCompletedSample(sample, stack);
                if (csv == null) {
                    return Sample.DISABLED;
                }
                sample.use = null;
            }
            if (available == null && sample.use == null) {
                available = sample;
            }
        }
        if (available == null) {
            return Sample.DISABLED;
        }
        available.cmd = cmd;
        available.info = info;
        available.stageCount = 0;
        VK10.vkCmdResetQueryPool(cmd, pool, available.firstQuery, QUERIES_PER_SAMPLE);
        return available;
    }

    private void initialize(RtContext ctx, MemoryStack stack) {
        initialized = true;
        VkDevice vk = ctx.vk();
        var physicalDevice = vk.getPhysicalDevice();
        var count = stack.mallocInt(1);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);
        int bits = families.get(ctx.device().graphicsQueue().queueFamilyIndex()).timestampValidBits();
        if (bits == 0) {
            CausticaMod.LOGGER.info("RT GPU frame stats unavailable on the graphics queue");
            return;
        }
        var properties = VkPhysicalDeviceProperties.malloc(stack);
        VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties);
        timestampMask = -1L >>> (64 - bits);
        millisecondsPerTick = properties.limits().timestampPeriod() / 1_000_000.0;

        var queryInfo = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(samples.length * QUERIES_PER_SAMPLE);
        var handle = stack.mallocLong(1);
        int result = VK10.vkCreateQueryPool(vk, queryInfo, null, handle);
        if (result != VK10.VK_SUCCESS) {
            CausticaMod.LOGGER.warn("Could not create RT GPU timestamp queries (Vulkan {})", result);
            return;
        }
        device = vk;
        pool = handle.get(0);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = new Sample(pool, i * QUERIES_PER_SAMPLE);
        }
        StringBuilder header = new StringBuilder("frame,view,outputWidth,outputHeight,wideTraceWidth,wideTraceHeight,"
                + "insetTraceWidth,insetTraceHeight,coverageDegrees,reusedInset,gazeState");
        for (Stage stage : STAGES) {
            header.append(',').append(stage.column).append("Ms");
        }
        csv = RtFrameStats.openCsv("gpu", header.toString());
    }

    private void writeCompletedSample(Sample sample, MemoryStack stack) {
        int queryCount = sample.stageCount * 2;
        LongBuffer ticks = stack.mallocLong(queryCount);
        // The timeline already proves completion. Never wait for this pool or request partial timestamps.
        int result = VK10.vkGetQueryPoolResults(device, pool, sample.firstQuery, queryCount,
                ticks, Long.BYTES, VK10.VK_QUERY_RESULT_64_BIT);
        if (result != VK10.VK_SUCCESS) {
            CausticaMod.LOGGER.warn("Could not read RT GPU timestamps (Vulkan {})", result);
            csv.close();
            csv = null;
            return;
        }
        Arrays.fill(stageStarts, -1);
        for (int i = 0; i < sample.stageCount; i++) {
            stageStarts[sample.stages[i].ordinal()] = i * 2;
        }
        FrameInfo info = sample.info;
        StringBuilder row = new StringBuilder().append(info.frame()).append(',').append(info.view())
                .append(',').append(info.outputWidth()).append(',').append(info.outputHeight())
                .append(',').append(info.wideWidth()).append(',').append(info.wideHeight())
                .append(',').append(info.insetWidth()).append(',').append(info.insetHeight())
                .append(',').append(info.coverageDegrees()).append(',').append(info.reusedInset() ? 1 : 0)
                .append(',').append(info.gazeState());
        for (int index : stageStarts) {
            row.append(',');
            if (index >= 0) {
                long elapsed = (ticks.get(index + 1) - ticks.get(index)) & timestampMask;
                double unsignedTicks = (elapsed >>> 1) * 2.0 + (elapsed & 1L);
                row.append(Math.round(unsignedTicks * millisecondsPerTick * 1000.0) / 1000.0);
            }
        }
        csv.println(row);
    }

    /** Called after the graphics device is idle. */
    void destroy() {
        if (pool != 0L) {
            VK10.vkDestroyQueryPool(device, pool, null);
            pool = 0L;
        }
        if (csv != null) {
            csv.close();
            csv = null;
        }
        Arrays.fill(samples, null);
        device = null;
        initialized = false;
    }

    static final class Sample {
        static final Sample DISABLED = new Sample(0L, 0);
        private final long pool;
        private final int firstQuery;
        private final Stage[] stages = new Stage[STAGES.length];
        private int stageCount;
        private VkCommandBuffer cmd;
        private FrameInfo info;
        private RtGpuExecutor.GraphicsUse use;

        private Sample(long pool, int firstQuery) {
            this.pool = pool;
            this.firstQuery = firstQuery;
        }

        RtFrameStats.Scope stage(Stage stage) {
            if (cmd == null) {
                return RtFrameStats.Scope.NOOP;
            }
            int index = stageCount++;
            stages[index] = stage;
            int query = firstQuery + index * 2;
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, query);
            return () -> VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, query + 1);
        }

        void submitted(RtGpuExecutor.GraphicsUse graphicsUse) {
            if (cmd != null) {
                use = graphicsUse;
                cmd = null;
            }
        }
    }
}
