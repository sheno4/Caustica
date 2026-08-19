package dev.comfyfluffy.caustica.api;

import java.util.List;

/**
 * The engine slots a composition binds exactly one feature to. Surfaces are deliberately absent: a
 * material names its own {@code ISurfaceModel} implementation, so they are registered as a set rather
 * than competing for one slot — see {@link FeatureBuilder#surface}.
 */
public final class Slots {
    public static final Slot SKY = slot("sky", "caustica_sky", "ISkyModel");
    public static final List<Slot> ALL = List.of(SKY);

    private Slots() {
    }

    private static Slot slot(String path, String module, String type) {
        return new Slot(ResourceId.of("caustica", path), module, type);
    }
}
