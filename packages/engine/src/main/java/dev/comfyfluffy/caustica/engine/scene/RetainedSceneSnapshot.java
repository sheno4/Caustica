package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.engine.program.ProgramResolution;

import java.util.List;
import java.util.Objects;

/** Immutable logical world publication consumed by later BLAS, TLAS, and light-upload backends. */
public record RetainedSceneSnapshot(long revision, List<Scene> scenes, List<Mesh> meshes,
                                    List<Instance> instances, List<Light> lights) {
    public RetainedSceneSnapshot {
        scenes = List.copyOf(scenes);
        meshes = List.copyOf(meshes);
        instances = List.copyOf(instances);
        lights = List.copyOf(lights);
    }

    public record Scene(SceneId id, EnvironmentBinding<?> environment) { }
    public record Mesh(long identity, MeshBuild<?> build, List<GeometryPrograms> geometryPrograms) {
        public Mesh {
            geometryPrograms = List.copyOf(geometryPrograms);
            if (geometryPrograms.size() != build.geometries().size()) {
                throw new IllegalArgumentException("each geometry needs one resolved program entry");
            }
        }
    }
    public record GeometryPrograms(ProgramResolution.Surface surface, ProgramResolution.Volume volume) {
        public GeometryPrograms {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(volume, "volume");
        }
    }
    public record Instance(long identity, SceneId scene, long meshIdentity,
                           GeometryTransform transform, int mask, ShaderData<?> instanceData) { }
    public record Light(long identity, SceneId scene, LightDescriptor descriptor) { }
}
