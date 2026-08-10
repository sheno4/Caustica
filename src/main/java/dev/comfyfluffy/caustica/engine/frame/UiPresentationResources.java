package dev.comfyfluffy.caustica.engine.frame;

/** Immutable host UI resources sampled at a presentation seam. */
public record UiPresentationResources(boolean enabled, boolean populated,
                                      long colorImage, long colorView,
                                      int width, int height) {
    public static final UiPresentationResources EMPTY =
            new UiPresentationResources(false, false, 0L, 0L, 0, 0);
}
