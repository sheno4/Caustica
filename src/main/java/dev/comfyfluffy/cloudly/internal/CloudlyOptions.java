/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.internal;

import dev.comfyfluffy.cloudly.settings.Option;

import java.util.List;

/** Shared user controls for the native Cloudly renderer and its source-volume adapter. */
public final class CloudlyOptions {
    public enum Scene { HANGAR, MISSION3, MISSION11 }

    public static final Option<Float> SUN_NOON_SOUTH_TILT_DEGREES = Option.range("sky.sun-noon-south-tilt-degrees", -90, 90, 0);

    public static final String GROUP = "sky-cloudly";
    public static final Option<Boolean> ENABLED = Option.bool("sky.cloudly.enabled", true).inGroupAsHeader(GROUP);
    public static final Option<Scene> SCENE = Option.enumOf("sky.cloudly.scene", Scene.HANGAR, List.of(Scene.values())).inGroup(GROUP);
    public static final Option<Integer> HEIGHT_OFFSET_BLOCKS = Option.integer("sky.cloudly.height-offset-blocks", -16000, 16000, 0).inGroup(GROUP);
    public static final Option<Float> MAX_DISTANCE_KM = Option.range("sky.cloudly.max-distance-km", 1, 200, 200).inGroup(GROUP);
    public static final List<Option<?>> OPTIONS = List.of(ENABLED, SCENE, HEIGHT_OFFSET_BLOCKS, MAX_DISTANCE_KM);

    private CloudlyOptions() { }
}
