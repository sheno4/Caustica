package dev.comfyfluffy.caustica.engine.frame;

import dev.comfyfluffy.caustica.api.vulkan.OwnedGpuImage;

/** Immutable host UI resources sampled at a presentation seam. */
public record UiPresentationResources(boolean enabled, boolean populated,
                                      OwnedGpuImage color,
                                      int width, int height) {
    public static final UiPresentationResources EMPTY =
            new UiPresentationResources(false, false, null, 0, 0);

    public long colorImage() { return color == null ? 0L : color.image(); }
    public long colorView() { return color == null ? 0L : color.view(); }
}
