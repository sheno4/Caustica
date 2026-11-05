package dev.comfyfluffy.caustica.minecraft.rendering.sky;

import dev.comfyfluffy.caustica.settings.Option;

/** Host sky option tokens read by the native Cloudly frame recipes. */
public final class SkyLutPass {
    public static final Option<Float> SUN_NOON_SOUTH_TILT_DEGREES =
            Option.range("sun-noon-south-tilt-degrees", -89, 89, 30);

    private SkyLutPass() { }
}
