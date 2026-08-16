package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in render-frame timing and hitch detection. Gated by {@code -Dcaustica.rt.frameStats}; every method
 * is a cheap branch when disabled. The profile begins when RT client-tick work starts (or at
 * {@code GameRenderer.render} HEAD when there was no RT tick work) and ends at render TAIL, so the hitch
 * decision uses one frame envelope rather than one action/pass at a time. Every completed frame is appended
 * as one row to {@code <outputDirectory>/frame.csv} (fresh file per session), and a log line is
 * emitted only when the frame exceeds {@value #HITCH_MULTIPLIER}x its rolling median. That hitch line
 * includes all detailed stage timings and counters recorded during the frame.
 */
public final class RtFrameStats {
    private static final int MEDIAN_WINDOW = 64;
    private static final double HITCH_MULTIPLIER = 1.5;
    private static final OutputLocation OUTPUT = new OutputLocation(defaultOutputDirectory());

    private static final MetricSchema RENDERER_FRAME_METRICS = new MetricSchema(List.of(
            new StageMetric("geometry.providerCollect", false),
            new StageMetric("geometry.providerConvert", true),
            new StageMetric("geometry.schedulerSubmit", true),
            new StageMetric("geometry.schedulerValidate", false),
            new StageMetric("geometry.publishTerminal", true),
            new StageMetric("geometry.prepareCandidates", true),
            new StageMetric("geometry.packMaterial", false),
            new StageMetric("geometry.snapshotAppend", true),
            new StageMetric("frame.prepareTlas", true),
            new StageMetric("frame.recordTlas", true),
            new StageMetric("frame.skyLut", true),
            new StageMetric("frame.tracePrimary", true),
            new StageMetric("frame.traceIndirect", true),
            new StageMetric("frame.exposure", true),
            new StageMetric("frame.dlssRr", true),
            new StageMetric("frame.upscale", true),
            new StageMetric("frame.postChain", true),
            new StageMetric("frame.displayMap", true),
            new StageMetric("frame.debugPresent", true),
            new StageMetric("frame.copyOutput", true)), List.of(
            "geometryGroupsSubmitted", "geometryPutsSubmitted", "geometryTrianglesSubmitted",
            "geometryGroupsAccepted", "geometryPutsAccepted",
            "geometryGroupRevisionsCoalesced", "geometryPutRevisionsCoalesced", "geometryPutsStarted",
            "geometryBlasCandidates", "geometryGroupsPublished", "geometryPutsPublished",
            "geometryInstancesVisible", "geometryPendingGroups", "geometryRunningGroups",
            "geometryTerminalGroups", "geometryPublishedResidents", "geometryPublishedPlacements",
            "geometryPlacementFreshnessApplied"));

    // Per-frame GC deltas help distinguish JVM pauses from uninstrumented render work when a hitch's
    // unaccounted time is large. The host appends its own producer metrics during bootstrap.
    public static final Profile FRAME = new Profile("frame", RENDERER_FRAME_METRICS, true);
    private static volatile long frameSerial;

    /** Monotonic identifier for the frame envelope currently collecting producer and renderer work. */
    public static long frameSerial() {
        return frameSerial;
    }

    /** Advance the serial once at the host render-frame boundary. */
    public static void beginRenderFrame() {
        frameSerial++;
    }

    private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private static long gcCollections() {
        long total = 0;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long count = bean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long time = bean.getCollectionTime();
            if (time > 0) {
                total += time;
            }
        }
        return total;
    }

    private RtFrameStats() {
    }

    /** One timed stage and whether its duration belongs in the frame's accounted-time sum. */
    public record StageMetric(String name, boolean contributesToAccountedTime) {
        public StageMetric {
            requireMetricName(name);
        }
    }

    /** Immutable stage/counter vocabulary contributed by the renderer or its host. */
    public record MetricSchema(List<StageMetric> stages, List<String> counters) {
        public MetricSchema {
            stages = List.copyOf(stages);
            counters = List.copyOf(counters);
            Set<String> names = new HashSet<>();
            for (StageMetric stage : stages) {
                Objects.requireNonNull(stage, "stage metric");
                if (!names.add(stage.name())) {
                    throw new IllegalArgumentException("Duplicate RtFrameStats name: " + stage.name());
                }
            }
            for (String counter : counters) {
                requireMetricName(counter);
                if (!names.add(counter)) {
                    throw new IllegalArgumentException("Duplicate RtFrameStats name: " + counter);
                }
            }
        }

        MetricSchema append(MetricSchema extension) {
            Objects.requireNonNull(extension, "metric schema");
            ArrayList<StageMetric> combinedStages = new ArrayList<>(stages.size() + extension.stages.size());
            combinedStages.addAll(stages);
            combinedStages.addAll(extension.stages);
            ArrayList<String> combinedCounters = new ArrayList<>(counters.size() + extension.counters.size());
            combinedCounters.addAll(counters);
            combinedCounters.addAll(extension.counters);
            return new MetricSchema(combinedStages, combinedCounters);
        }

        long accountedNanos(long[] stageNanos) {
            if (stageNanos.length != stages.size()) {
                throw new IllegalArgumentException("stage duration count does not match metric schema");
            }
            long total = 0L;
            for (int i = 0; i < stages.size(); i++) {
                if (stages.get(i).contributesToAccountedTime()) {
                    total += stageNanos[i];
                }
            }
            return total;
        }
    }

    /** Append host frame metrics before the frame profile is first used. */
    public static void configureFrameMetrics(MetricSchema metrics) {
        FRAME.configureMetrics(metrics);
    }

    static MetricSchema rendererFrameMetrics() {
        return RENDERER_FRAME_METRICS;
    }

    /**
     * Select the directory profiles lazily create their CSV files in. Bootstrap must call this before any
     * profile attempts to open its writer; changing the directory after that point is an error.
     */
    public static void configureOutputDirectory(Path directory) {
        OUTPUT.configure(directory);
    }

    private static void requireMetricName(String name) {
        Objects.requireNonNull(name, "metric name");
        if (name.isBlank() || name.indexOf(',') >= 0) {
            throw new IllegalArgumentException("Invalid RtFrameStats name: " + name);
        }
    }

    static Path defaultOutputDirectory() {
        return Path.of(System.getProperty("user.dir", "."), "rt-frame-stats")
                .toAbsolutePath().normalize();
    }

    static final class OutputLocation {
        private Path directory;
        private boolean writerInitializationAttempted;

        OutputLocation(Path directory) {
            this.directory = normalize(directory);
        }

        synchronized void configure(Path directory) {
            if (writerInitializationAttempted) {
                throw new IllegalStateException("RtFrameStats output directory is fixed after writer initialization");
            }
            this.directory = normalize(directory);
        }

        synchronized Path beginWriterInitialization() {
            writerInitializationAttempted = true;
            return directory;
        }

        synchronized Path directory() {
            return directory;
        }

        private static Path normalize(Path directory) {
            return Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        }
    }

    public static boolean enabled() {
        return CausticaConfig.Rt.FrameStats.ENABLED.value();
    }

    public interface Scope extends AutoCloseable {
        Scope NOOP = () -> {};

        @Override
        void close();
    }

    /** A timed render frame: per-stage nanos + named counters, plus a rolling-median hitch log. */
    public static final class Profile {
        private final String name;
        private final MetricSchema baseMetrics;
        private final boolean trackGc;
        private MetricSchema metrics;
        private String[] stageNames;
        private String[] counterNames;
        private Map<String, Integer> stageIndices;
        private Map<String, Integer> counterIndices;
        private long[] stageNanos;
        private long[] counters;
        private final long[] history = new long[MEDIAN_WINDOW];
        private int historyCount;
        private int historyPos;
        private long frameStart;
        private long frameIndex;
        private long gcCountStart;
        private long gcMsStart;
        private PrintWriter csv;
        private boolean csvOpenAttempted;
        private boolean active;
        private boolean metricsConfigured;
        private volatile boolean metricsUsed;

        Profile(String name, MetricSchema metrics, boolean trackGc) {
            this.name = name;
            this.baseMetrics = Objects.requireNonNull(metrics, "metrics");
            this.trackGc = trackGc;
            applyMetrics(metrics);
        }

        synchronized void configureMetrics(MetricSchema extension) {
            if (metricsUsed) {
                throw new IllegalStateException("RtFrameStats metrics are fixed after first profile use");
            }
            if (metricsConfigured) {
                throw new IllegalStateException("RtFrameStats metrics are already configured");
            }
            applyMetrics(baseMetrics.append(extension));
            metricsConfigured = true;
        }

        private void applyMetrics(MetricSchema configured) {
            this.metrics = configured;
            this.stageNames = configured.stages().stream().map(StageMetric::name).toArray(String[]::new);
            this.counterNames = configured.counters().toArray(String[]::new);
            this.stageNanos = new long[stageNames.length];
            this.counters = new long[counterNames.length];
            this.stageIndices = index(stageNames);
            this.counterIndices = index(counterNames);
        }

        /** Start timing a new frame; clears this frame's stage/counter accumulators. */
        public void begin() {
            metricsUsed = true;
            active = false;
            if (!enabled()) {
                return;
            }
            active = true;
            frameStart = System.nanoTime();
            Arrays.fill(stageNanos, 0L);
            Arrays.fill(counters, 0L);
            if (trackGc) {
                gcCountStart = gcCollections();
                gcMsStart = gcMillis();
            }
        }

        /** Start a frame only when one is not already collecting RT tick details for this render. */
        public void beginIfInactive() {
            if (!active) {
                begin();
            }
        }

        /** Time one named stage of the current frame; close the returned scope when the stage completes. */
        public Scope stage(String stageName) {
            if (!enabled() || !active) {
                return Scope.NOOP;
            }
            int idx = indexOf(stageIndices, stageName);
            long start = System.nanoTime();
            return () -> stageNanos[idx] += System.nanoTime() - start;
        }

        /**
         * Start a hot-path stage without allocating a {@link Scope}. Returns zero while profiling is inactive;
         * pass the value unchanged to {@link #endStage(String, long)}.
         */
        public long startStage() {
            return enabled() && active ? System.nanoTime() : 0L;
        }

        /** Finish a stage started by {@link #startStage()}. */
        public void endStage(String stageName, long startNanos) {
            if (startNanos == 0L) {
                return;
            }
            stageNanos[indexOf(stageIndices, stageName)] += System.nanoTime() - startNanos;
        }

        /** Add to a named counter for the current frame. */
        public void count(String counterName, long delta) {
            if (!enabled() || !active || delta == 0) {
                return;
            }
            counters[indexOf(counterIndices, counterName)] += delta;
        }

        /** Replace a current-frame counter, typically for an instantaneous queue depth. */
        public void set(String counterName, long value) {
            if (!enabled() || !active) {
                return;
            }
            counters[indexOf(counterIndices, counterName)] = value;
        }

        /** Retain the largest value observed for a current-frame counter. */
        public void max(String counterName, long value) {
            if (!enabled() || !active) {
                return;
            }
            int index = indexOf(counterIndices, counterName);
            counters[index] = Math.max(counters[index], value);
        }

        /** Returns the current frame's accumulated value for a configured counter. */
        public long counterValue(String counterName) {
            return counters[indexOf(counterIndices, counterName)];
        }

        /** Finish the current frame: record it into the rolling median and log a hitch line if it's slow. */
        public void end() {
            if (!active) {
                return;
            }
            active = false;
            if (!enabled()) {
                return;
            }
            long total = System.nanoTime() - frameStart;
            long gcCount = trackGc ? gcCollections() - gcCountStart : 0;
            long gcMs = trackGc ? gcMillis() - gcMsStart : 0;
            long median = median();
            history[historyPos] = total;
            historyPos = (historyPos + 1) % MEDIAN_WINDOW;
            if (historyCount < MEDIAN_WINDOW) {
                historyCount++;
            }
            boolean hitch = median > 0 && total > (long) (median * HITCH_MULTIPLIER);
            writeCsvRow(total, median, hitch, gcCount, gcMs);
            if (hitch) {
                logHitch(total, median, gcCount, gcMs);
            }
        }

        private long median() {
            if (historyCount == 0) {
                return 0L;
            }
            long[] sorted = Arrays.copyOf(history, historyCount);
            Arrays.sort(sorted);
            return sorted[historyCount / 2];
        }

        private void writeCsvRow(long total, long median, boolean hitch, long gcCount, long gcMs) {
            ensureCsv();
            if (csv == null) {
                return;
            }
            StringBuilder row = new StringBuilder();
            row.append(frameIndex++).append(',').append(ms(total)).append(',').append(ms(median)).append(',').append(hitch ? 1 : 0);
            for (long stageNano : stageNanos) {
                row.append(',').append(ms(stageNano));
            }
            for (long counter : counters) {
                row.append(',').append(counter);
            }
            if (trackGc) {
                row.append(',').append(gcCount).append(',').append(gcMs);
            }
            csv.println(row);
            csv.flush();
        }

        /** Opens (or gives up on) this profile's CSV file at most once per session. */
        private void ensureCsv() {
            if (csvOpenAttempted) {
                return;
            }
            csvOpenAttempted = true;
            Path dir = OUTPUT.beginWriterInitialization();
            Path file = dir.resolve(name + ".csv");
            try {
                Files.createDirectories(dir);
                csv = new PrintWriter(new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8)));
                StringBuilder header = new StringBuilder("frame,totalMs,medianMs,hitch");
                for (String stageName : stageNames) {
                    header.append(',').append(stageName).append("Ms");
                }
                for (String counterName : counterNames) {
                    header.append(',').append(counterName);
                }
                if (trackGc) {
                    header.append(",gcCount,gcPauseMs");
                }
                csv.println(header);
                csv.flush();
            } catch (IOException e) {
                CausticaMod.LOGGER.warn("RtFrameStats: failed to open CSV {} for profile {}: {}", file, name, e.toString());
                csv = null;
            }
        }

        private void logHitch(long total, long median, long gcCount, long gcMs) {
            StringBuilder sb = new StringBuilder("RT hitch [").append(name).append("] ")
                    .append(ms(total)).append("ms (median ").append(ms(median)).append("ms):");
            for (int i = 0; i < stageNames.length; i++) {
                sb.append(' ').append(stageNames[i]).append('=').append(ms(stageNanos[i])).append("ms");
            }
            // Time inside this frame not covered by any stage timer — a big value here with gcPause>0 means
            // a GC pause landed mid-frame; with gcPause=0 it points at an uninstrumented stage.
            sb.append(" unaccounted=")
                    .append(ms(Math.max(0, total - metrics.accountedNanos(stageNanos)))).append("ms");
            for (int i = 0; i < counterNames.length; i++) {
                sb.append(' ').append(counterNames[i]).append('=').append(counters[i]);
            }
            if (trackGc) {
                sb.append(" gcCount=").append(gcCount).append(" gcPauseMs=").append(gcMs);
            }
            CausticaMod.LOGGER.info(sb.toString());
        }

        private static double ms(long nanos) {
            return Math.round(nanos / 1000.0) / 1000.0;
        }

        private static Map<String, Integer> index(String[] names) {
            Map<String, Integer> result = new HashMap<>(names.length * 2);
            for (int i = 0; i < names.length; i++) {
                if (result.put(names[i], i) != null) {
                    throw new IllegalArgumentException("Duplicate RtFrameStats name: " + names[i]);
                }
            }
            return result;
        }

        private static int indexOf(Map<String, Integer> indices, String name) {
            Integer index = indices.get(name);
            if (index == null) {
                throw new IllegalArgumentException("Unknown RtFrameStats name: " + name);
            }
            return index;
        }
    }
}
