package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;

import java.util.Objects;

/** Renderer-facing transform-only overlay for one retained placement identity. */
public record RetainedInstanceTransform(long identity, GeometryTransform transform, int mask) {
    public RetainedInstanceTransform {
        Objects.requireNonNull(transform, "transform");
        if ((mask & ~0xFF) != 0) throw new IllegalArgumentException("visibility mask must fit in eight bits");
    }
}
