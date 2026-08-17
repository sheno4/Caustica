package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;

import java.util.List;

/** Immutable protocol for atomically updating retained renderer geometry. */
public final class GeometryUpdates {
    private GeometryUpdates() {
    }

    /** Stable owner identity for one independently published retained-geometry group. */
    public record GroupKey(ResourceId source, SceneGeometryKey key) {
        public GroupKey(ResourceId source, long key) {
            this(source, SceneGeometryKey.of(key));
        }
    }

    /** Mesh data packed into the renderer format when its atomic update starts. */
    public sealed interface GeometryPayload permits ProviderPayload {
    }

    public record ProviderPayload(SceneMesh mesh, SceneGeometrySink.BuildOptions buildOptions)
            implements GeometryPayload {
        public ProviderPayload(SceneMesh mesh) {
            this(mesh, SceneGeometrySink.BuildOptions.DEFAULT);
        }
    }

    /** Immutable operation belonging to one atomic geometry group. */
    public sealed interface GeometryOperation permits Put, Drop, Place, UpdatePlacement, Remove {
    }

    public record Put(SceneGeometryKey residentKey, GeometryPayload payload) implements GeometryOperation {
        public Put(long residentKey, GeometryPayload payload) {
            this(SceneGeometryKey.of(residentKey), payload);
        }
    }

    public record Drop(SceneGeometryKey residentKey) implements GeometryOperation {
        public Drop(long residentKey) {
            this(SceneGeometryKey.of(residentKey));
        }
    }

    public record Place(SceneGeometryKey instanceKey, SceneGeometryKey residentKey, float[] transform, int mask,
                        SceneOrigin origin) implements GeometryOperation {
        public Place(long instanceKey, long residentKey, float[] transform, int mask, SceneOrigin origin) {
            this(SceneGeometryKey.of(instanceKey), SceneGeometryKey.of(residentKey), transform, mask, origin);
        }

        public Place(long instanceKey, long residentKey, float[] transform, int mask) {
            this(SceneGeometryKey.of(instanceKey), SceneGeometryKey.of(residentKey), transform, mask,
                    SceneOrigin.ZERO);
        }

        public Place {
            transform = transform.clone();
        }

        @Override
        public float[] transform() {
            return transform.clone();
        }
    }

    public record UpdatePlacement(SceneGeometryKey instanceKey, float[] transform, int mask, SceneOrigin origin)
            implements GeometryOperation {
        public UpdatePlacement(long instanceKey, float[] transform, int mask) {
            this(SceneGeometryKey.of(instanceKey), transform, mask, SceneOrigin.ZERO);
        }

        public UpdatePlacement {
            transform = transform.clone();
        }

        @Override
        public float[] transform() {
            return transform.clone();
        }
    }

    public record Remove(SceneGeometryKey instanceKey) implements GeometryOperation {
        public Remove(long instanceKey) {
            this(SceneGeometryKey.of(instanceKey));
        }
    }

    /** A source submission that either replaces its whole published snapshot or remains unchanged. */
    public record Group(GroupKey key, long revision, List<GeometryOperation> operations) {
        public Group {
            operations = List.copyOf(operations);
        }
    }

    /** One completed publication with the exact final operations applied to the global maps. */
    public record Publication(GroupKey key, long revision, List<GeometryOperation> operations) {
    }
}
