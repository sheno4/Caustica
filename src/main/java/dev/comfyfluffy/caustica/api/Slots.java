package dev.comfyfluffy.caustica.api;

import net.minecraft.resources.Identifier;

import java.util.List;

public final class Slots {
    public static final Slot SKY = slot("sky", "caustica_sky", "ISkyModel");
    public static final Slot SURFACE = slot("surface", "caustica_surface", "ISurfaceModel");
    public static final List<Slot> ALL = List.of(SKY, SURFACE);

    private Slots() {
    }

    private static Slot slot(String path, String module, String type) {
        return new Slot(Identifier.fromNamespaceAndPath("caustica", path), module, type);
    }
}
