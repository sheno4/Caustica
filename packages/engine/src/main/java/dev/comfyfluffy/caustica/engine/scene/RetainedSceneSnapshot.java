package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;

import java.util.List;

/** Immutable scene values referencing prepared meshes. Frame capture retains independent resource claims. */
public record RetainedSceneSnapshot(long revision, List<Scene> scenes, List<Mesh> meshes,
                                    List<Instance> instances, List<Light> lights) {
    public RetainedSceneSnapshot {
        scenes = List.copyOf(scenes);
        meshes = List.copyOf(meshes);
        instances = List.copyOf(instances);
        lights = List.copyOf(lights);
    }

    public record Scene(SceneId id, EnvironmentBinding<?> environment) { }

    /** Whether vertex indices preserve the same ordered vertex correspondence across two builds. */
    public static boolean vertexTopologyCompatible(MeshBuild<?> previous, MeshBuild<?> current) {
        if (previous == null || previous.vertexCount() != current.vertexCount()
                || previous.indexRevision() == null
                || !previous.indexRevision().equals(current.indexRevision())
                || previous.geometries().size() != current.geometries().size()) {
            return false;
        }
        for (int index = 0; index < previous.geometries().size(); index++) {
            MeshBuild.Geometry<?> first = previous.geometries().get(index);
            MeshBuild.Geometry<?> second = current.geometries().get(index);
            if (first.firstIndex() != second.firstIndex() || first.indexCount() != second.indexCount()) {
                return false;
            }
        }
        return true;
    }

    public record Mesh(long identity, MeshBuild<?> build, List<GeometryPrograms> geometryPrograms,
                       dev.comfyfluffy.caustica.api.geometry.ReadyMesh<?> ready) {
        public Mesh {
            java.util.Objects.requireNonNull(build, "build");
            geometryPrograms = List.copyOf(geometryPrograms);
            if (geometryPrograms.size() != build.geometries().size()) {
                throw new IllegalArgumentException("each geometry needs one resolved program entry");
            }
        }
    }
    /** Zero selects the built-in error surface or vacuum volume. */
    public record GeometryPrograms(int surfaceImplementation, int volumeImplementation) {
        public GeometryPrograms {
            if (surfaceImplementation < 0 || volumeImplementation < 0) {
                throw new IllegalArgumentException("program implementation indices must be non-negative");
            }
        }
    }
    public record Instance(long identity, SceneId scene, long meshIdentity,
                           GeometryTransform transform, int mask, ShaderData<?> instanceData,
                           List<PrimitiveEmitter> primitiveEmitters) {
        public Instance { primitiveEmitters = List.copyOf(primitiveEmitters); }
    }
    public record PrimitiveEmitter(int firstPrimitive, int primitiveCount, long lightIdentity) { }
    public record Light(long identity, SceneId scene, LightDescriptor descriptor) { }
}
