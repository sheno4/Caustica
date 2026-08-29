package dev.comfyfluffy.caustica.rt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;

/** Renderer telemetry consumed by the first-party Minecraft integration. */
public interface RtTelemetry {
    enum GeometrySource {
        TERRAIN, TERRAIN_READY, ENTITY, ENTITY_PLACEMENT, BLOCK_ENTITY, PARTICLE
    }

    /** Opaque extraction sample carried by Minecraft geometry until its publication callback runs. */
    interface ExtractionStamp {
    }

    interface Scope extends AutoCloseable {
        Scope NOOP = () -> { };

        @Override
        void close();
    }

    interface Frame {
        Scope stage(String name);

        long startStage();

        void endStage(String name, long startedNanos);

        void count(String name, long delta);

        void set(String name, long value);

        void max(String name, long value);
    }

    /** One timed stage and whether its duration belongs in the frame's accounted-time sum. */
    record StageMetric(String name, boolean contributesToAccountedTime) {
        public StageMetric {
            requireMetricName(name);
        }
    }

    /** Immutable stage and counter vocabulary contributed by Minecraft. */
    record MetricSchema(List<StageMetric> stages, List<String> counters) {
        public MetricSchema {
            stages = List.copyOf(stages);
            counters = List.copyOf(counters);
            Set<String> names = new HashSet<>();
            for (StageMetric stage : stages) {
                Objects.requireNonNull(stage, "stage metric");
                if (!names.add(stage.name())) {
                    throw new IllegalArgumentException("Duplicate telemetry name: " + stage.name());
                }
            }
            for (String counter : counters) {
                requireMetricName(counter);
                if (!names.add(counter)) {
                    throw new IllegalArgumentException("Duplicate telemetry name: " + counter);
                }
            }
        }

        public MetricSchema append(MetricSchema extension) {
            Objects.requireNonNull(extension, "metric schema");
            ArrayList<StageMetric> combinedStages = new ArrayList<>(stages.size() + extension.stages.size());
            combinedStages.addAll(stages);
            combinedStages.addAll(extension.stages);
            ArrayList<String> combinedCounters = new ArrayList<>(counters.size() + extension.counters.size());
            combinedCounters.addAll(counters);
            combinedCounters.addAll(extension.counters);
            return new MetricSchema(combinedStages, combinedCounters);
        }

        public long accountedNanos(long[] stageNanos) {
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

    boolean enabled();

    long frameSerial();

    Frame frame();

    void beginRenderFrame();

    void beginFrameIfInactive();

    void endFrame();

    void configure(Path outputDirectory, MetricSchema minecraftMetrics);

    ExtractionStamp extraction(GeometrySource source, int geometryCount);

    void published(ExtractionStamp stamp);

    void afterPublicationVisible(LongConsumer action);

    private static void requireMetricName(String name) {
        Objects.requireNonNull(name, "metric name");
        if (name.isBlank() || name.indexOf(',') >= 0) {
            throw new IllegalArgumentException("Invalid telemetry name: " + name);
        }
    }
}
