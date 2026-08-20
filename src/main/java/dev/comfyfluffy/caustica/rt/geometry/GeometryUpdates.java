package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;

import java.util.List;
import java.util.Objects;

/** Immutable protocol for atomically updating retained renderer geometry. */
public final class GeometryUpdates {
    private GeometryUpdates() {
    }

    /** Stable owner identity for one independently published retained-geometry group. */
    public record GroupKey(ResourceId source, SceneGeometryKey key) {
    }

    /** Mesh data packed into the renderer format when its atomic update starts. */
    public sealed interface GeometryPayload permits ProviderPayload {
    }

    public record ProviderPayload(SceneMesh mesh, BuildPolicy buildPolicy)
            implements GeometryPayload {
        public ProviderPayload {
            Objects.requireNonNull(mesh, "mesh");
            Objects.requireNonNull(buildPolicy, "buildPolicy");
        }

        public SceneMesh.TopologyRevision topologyRevision() {
            return mesh.topologyRevision();
        }
    }

    public enum BuildPolicy {
        DYNAMIC,
        STATIC
    }

    /** Immutable operation belonging to one atomic geometry group. */
    public sealed interface GeometryOperation permits Put, Drop, Place, UpdatePlacement, Remove {
    }

    public record Put(SceneGeometryKey residentKey, GeometryPayload payload) implements GeometryOperation {
    }

    public record Drop(SceneGeometryKey residentKey) implements GeometryOperation {
    }

    public record Place(SceneGeometryKey instanceKey, SceneGeometryKey residentKey, float[] transform, int mask,
                        SceneOrigin origin) implements GeometryOperation {
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
        public UpdatePlacement {
            transform = transform.clone();
        }

        @Override
        public float[] transform() {
            return transform.clone();
        }
    }

    public record Remove(SceneGeometryKey instanceKey) implements GeometryOperation {
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
