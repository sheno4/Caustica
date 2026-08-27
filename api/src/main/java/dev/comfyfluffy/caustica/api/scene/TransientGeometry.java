package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.scene.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.scene.geometry.MeshBuild;

import java.util.Objects;

/** Geometry built, placed, and retained only for the frame receiving it. */
public record TransientGeometry(MeshBuild mesh, GeometryTransform transform, int mask, long properties) {
    public TransientGeometry {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(transform, "transform");
        if ((mask & ~0xFF) != 0) {
            throw new IllegalArgumentException("visibility mask must fit in eight bits");
        }
    }
}
