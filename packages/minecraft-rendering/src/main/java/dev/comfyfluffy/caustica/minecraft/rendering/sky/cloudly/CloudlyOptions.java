package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.settings.Option;

import java.util.List;

/** Shared user controls for the native Cloudly renderer and its source-volume adapter. */
public final class CloudlyOptions {
    public static final String GROUP = "sky-cloudly";
    public static final Option<Boolean> ENABLED = Option.bool("sky.cloudly.enabled", true).inGroupAsHeader(GROUP);
    public static final Option<Float> SAMPLES = Option.range("sky.cloudly.samples", 64, 1024, 128).inGroup(GROUP).step(32);
    public static final Option<Float> MAX_DISTANCE_KM = Option.range("sky.cloudly.max-distance-km", 1, 200, 200).inGroup(GROUP);
    public static final List<Option<?>> OPTIONS = List.of(ENABLED, SAMPLES, MAX_DISTANCE_KM);

    private CloudlyOptions() { }
}
