package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.rt.RtTelemetry;

import java.util.Objects;
import java.util.function.LongConsumer;

/** Application-owned instrumentation seam used by Minecraft scene producers. */
public final class MinecraftTelemetry {
    public enum GeometrySource {
        TERRAIN, TERRAIN_READY, ENTITY, ENTITY_PLACEMENT, BLOCK_ENTITY, PARTICLE
    }

    public interface Instrumentation {
        boolean enabled();

        long frameSerial();

        long startStage();

        void endStage(String name, long startedNanos);

        void count(String name, long delta);

        void max(String name, long value);

        Object extraction(GeometrySource source, int geometryCount);

        void published(Object stamp);

        void afterPublicationVisible(LongConsumer action);
    }

    private static volatile Instrumentation instrumentation = Disabled.INSTANCE;

    private MinecraftTelemetry() {
    }

    public static void install(RtTelemetry telemetry) {
        instrumentation = new RendererBridge(Objects.requireNonNull(telemetry, "telemetry"));
    }

    public static Instrumentation current() {
        return instrumentation;
    }

    private record RendererBridge(RtTelemetry telemetry) implements Instrumentation {
        @Override public boolean enabled() { return telemetry.enabled(); }
        @Override public long frameSerial() { return telemetry.frameSerial(); }
        @Override public long startStage() { return telemetry.frame().startStage(); }
        @Override public void endStage(String name, long startedNanos) {
            telemetry.frame().endStage(name, startedNanos);
        }
        @Override public void count(String name, long delta) { telemetry.frame().count(name, delta); }
        @Override public void max(String name, long value) { telemetry.frame().max(name, value); }
        @Override public Object extraction(GeometrySource source, int geometryCount) {
            return telemetry.extraction(switch (source) {
                case TERRAIN -> RtTelemetry.GeometrySource.TERRAIN;
                case TERRAIN_READY -> RtTelemetry.GeometrySource.TERRAIN_READY;
                case ENTITY -> RtTelemetry.GeometrySource.ENTITY;
                case ENTITY_PLACEMENT -> RtTelemetry.GeometrySource.ENTITY_PLACEMENT;
                case BLOCK_ENTITY -> RtTelemetry.GeometrySource.BLOCK_ENTITY;
                case PARTICLE -> RtTelemetry.GeometrySource.PARTICLE;
            }, geometryCount);
        }
        @Override public void published(Object stamp) {
            telemetry.published((RtTelemetry.ExtractionStamp) stamp);
        }
        @Override public void afterPublicationVisible(LongConsumer action) {
            telemetry.afterPublicationVisible(action);
        }
    }

    private enum Disabled implements Instrumentation {
        INSTANCE;

        @Override public boolean enabled() { return false; }
        @Override public long frameSerial() { return 0L; }
        @Override public long startStage() { return 0L; }
        @Override public void endStage(String name, long startedNanos) { }
        @Override public void count(String name, long delta) { }
        @Override public void max(String name, long value) { }
        @Override public Object extraction(GeometrySource source, int geometryCount) { return null; }
        @Override public void published(Object stamp) { }
        @Override public void afterPublicationVisible(LongConsumer action) { }
    }
}
