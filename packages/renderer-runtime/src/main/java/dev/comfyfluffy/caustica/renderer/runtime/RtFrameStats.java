package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.Frame;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.MetricSchema;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.StageMetric;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import jdk.jfr.*;

/** Raw CPU frame observations. Recording and analysis belong to consumers of the JFR events. */
public final class RtFrameStats {
    private static final com.sun.management.ThreadMXBean THREADS =
            java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
    private static final EventType FRAME_EVENT = EventType.getEventType(FrameEvent.class);
    private static final EventType STAGE_EVENT = EventType.getEventType(CpuStageEvent.class);
    private static final EventType COUNTER_EVENT = EventType.getEventType(FrameCounterEvent.class);
    private static final MetricSchema RENDERER_FRAME_METRICS = new MetricSchema(List.of(
            new StageMetric("geometry.providerCollect"),
            new StageMetric("geometry.providerConvert"),
            new StageMetric("geometry.schedulerSubmit"),
            new StageMetric("geometry.schedulerValidate"),
            new StageMetric("geometry.publishTerminal"),
            new StageMetric("geometry.prepareCandidates"),
            new StageMetric("geometry.packMaterial"),
            new StageMetric("geometry.snapshotAppend"),
            new StageMetric("frame.prepareWorldGeometry"),
            new StageMetric("frame.captureScenes"),
            new StageMetric("frame.assembleScenes"),
            new StageMetric("frame.recordTlas"),
            new StageMetric("frame.prepareLighting"),
            new StageMetric("frame.finishTrace"),
            new StageMetric("frame.skyLut"),
            new StageMetric("frame.buildStablePlanes"),
            new StageMetric("frame.fillStablePlanes"),
            new StageMetric("frame.bakeLocal"),
            new StageMetric("frame.exposure"),
            new StageMetric("frame.dlssRr"),
            new StageMetric("frame.nrd"),
            new StageMetric("frame.rawCopy"),
            new StageMetric("frame.upscale"),
            new StageMetric("frame.postChain"),
            new StageMetric("frame.displayMap"),
            new StageMetric("frame.debugPresent"),
            new StageMetric("frame.copyOutput")), List.of());

    private volatile long frameSerial;
    private boolean renderFrameStarted;
    private final Profile frame = new Profile("frame", RENDERER_FRAME_METRICS,
            () -> renderFrameStarted ? frameSerial : frameSerial + 1L);

    public long frameSerial() { return frameSerial; }
    public void beginRenderFrame() { frameSerial++; renderFrameStarted = true; }
    public void endFrame() { frame.end(); renderFrameStarted = false; }
    RtFrameStats() { }
    public void configureFrameMetrics(MetricSchema metrics) { frame.configureMetrics(metrics); }
    public Profile frame() { return frame; }
    public RtTelemetry.FrameSnapshot latestFrame() { return frame.latest; }
    static MetricSchema rendererFrameMetrics() { return RENDERER_FRAME_METRICS; }

    public static boolean enabled() {
        return FRAME_EVENT.isEnabled() || STAGE_EVENT.isEnabled() || COUNTER_EVENT.isEnabled();
    }

    public interface Scope extends RtTelemetry.Scope {
        Scope NOOP = () -> { };
    }

    /** Frame counters are accumulated on the host render thread; stages retain every invocation. */
    public static final class Profile implements Frame {
        private final String name;
        private final MetricSchema baseMetrics;
        private final LongSupplier frameSerial;
        private String[] counterNames;
        private Map<String, Integer> stageIndices;
        private Map<String, Integer> counterIndices;
        private long[] counters;
        private long frameStart;
        private long frameCpuStart;
        private long frameAllocationStart;
        private volatile RtTelemetry.FrameSnapshot latest;
        private boolean active;
        private boolean metricsConfigured;
        private boolean metricsUsed;

        Profile(String name, MetricSchema metrics, LongSupplier frameSerial) {
            this.name = name;
            this.baseMetrics = Objects.requireNonNull(metrics);
            this.frameSerial = frameSerial;
            applyMetrics(metrics);
        }

        synchronized void configureMetrics(MetricSchema extension) {
            if (metricsUsed) throw new IllegalStateException("Metrics are fixed after first profile use");
            if (metricsConfigured) throw new IllegalStateException("Metrics are already configured");
            applyMetrics(baseMetrics.append(extension));
            metricsConfigured = true;
        }

        private void applyMetrics(MetricSchema metrics) {
            stageIndices = index(metrics.stages().stream().map(StageMetric::name).toArray(String[]::new));
            counterNames = metrics.counters().toArray(String[]::new);
            counterIndices = index(counterNames);
            counters = new long[counterNames.length];
        }

        public void begin() {
            metricsUsed = true;
            active = enabled();
            if (!active) return;
            frameStart = System.nanoTime();
            frameCpuStart = THREADS.getCurrentThreadCpuTime();
            frameAllocationStart = THREADS.getCurrentThreadAllocatedBytes();
            Arrays.fill(counters, 0L);
        }

        public void beginIfInactive() { if (!active) begin(); }

        public Scope stage(String stageName) {
            long started = startStage();
            return started == 0L ? Scope.NOOP : () -> endStage(stageName, started);
        }

        public long startStage() {
            return active && STAGE_EVENT.isEnabled() ? System.nanoTime() : 0L;
        }

        public void endStage(String stageName, long startedNanos) {
            if (startedNanos == 0L) return;
            indexOf(stageIndices, stageName);
            CpuStageEvent event = new CpuStageEvent();
            event.frameId = frameSerial.getAsLong();
            event.stage = stageName;
            event.startedNanos = startedNanos;
            event.elapsedNanos = System.nanoTime() - startedNanos;
            event.commit();
        }

        public void count(String counterName, long delta) {
            if (active) counters[indexOf(counterIndices, counterName)] += delta;
        }
        public void set(String counterName, long value) {
            if (active) counters[indexOf(counterIndices, counterName)] = value;
        }
        public long counterValue(String counterName) { return counters[indexOf(counterIndices, counterName)]; }

        public void end() {
            if (!active) return;
            active = false;
            long frameId = frameSerial.getAsLong();
            FrameEvent event = new FrameEvent();
            event.frameId = frameId;
            event.profile = name;
            event.startedNanos = frameStart;
            event.elapsedNanos = System.nanoTime() - frameStart;
            event.threadCpuNanos = THREADS.getCurrentThreadCpuTime() - frameCpuStart;
            event.allocatedBytes = THREADS.getCurrentThreadAllocatedBytes() - frameAllocationStart;
            event.commit();
            Map<String, Long> values = new HashMap<>();
            for (int i = 0; i < counterNames.length; i++) values.put(counterNames[i], counters[i]);
            latest = new RtTelemetry.FrameSnapshot(frameId, frameStart, event.elapsedNanos, Map.copyOf(values));
            if (COUNTER_EVENT.isEnabled()) {
                for (int i = 0; i < counterNames.length; i++) {
                    FrameCounterEvent counter = new FrameCounterEvent();
                    counter.frameId = frameId;
                    counter.counter = counterNames[i];
                    counter.value = counters[i];
                    counter.commit();
                }
            }
        }

        private static Map<String, Integer> index(String[] names) {
            Map<String, Integer> result = new HashMap<>();
            for (int i = 0; i < names.length; i++) result.put(names[i], i);
            return result;
        }
        private static int indexOf(Map<String, Integer> indices, String name) {
            Integer index = indices.get(name);
            if (index == null) throw new IllegalArgumentException("Unknown telemetry metric: " + name);
            return index;
        }
    }

    @Name("dev.comfyfluffy.caustica.Frame")
    @Label("CPU frame envelope") @Category({"Caustica", "Frame"}) @StackTrace(false) @Enabled(false)
    static final class FrameEvent extends Event {
        long frameId;
        String profile;
        @Label("System.nanoTime start") long startedNanos;
        @Timespan(Timespan.NANOSECONDS) long elapsedNanos;
        @Label("Render thread CPU time") @Timespan(Timespan.NANOSECONDS) long threadCpuNanos;
        @Label("Render thread allocated bytes") @DataAmount(DataAmount.BYTES) long allocatedBytes;
    }

    @Name("dev.comfyfluffy.caustica.CpuStage")
    @Label("CPU stage elapsed time") @Category({"Caustica", "Frame"}) @StackTrace(false) @Enabled(false)
    static final class CpuStageEvent extends Event {
        long frameId;
        String stage;
        @Label("System.nanoTime start") long startedNanos;
        @Timespan(Timespan.NANOSECONDS) long elapsedNanos;
    }

    @Name("dev.comfyfluffy.caustica.FrameCounter")
    @Label("Frame counter") @Category({"Caustica", "Frame"}) @StackTrace(false) @Enabled(false)
    static final class FrameCounterEvent extends Event {
        long frameId;
        String counter;
        long value;
    }
}
