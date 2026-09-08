package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.PrimitiveLightMap;
import dev.comfyfluffy.caustica.api.geometry.ReadyMesh;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.ShaderData;

import java.util.Objects;

/** One change in an atomic scene edit. Instance and light identities grant owner-local mutation authority. */
public sealed interface SceneEdit {
    /**
     * Creates or replaces one placement. The ready mesh may be shared across contributions and scenes;
     * the instance identity belongs to this channel. Translation uses the target scene's coordinate units.
     * Instance data is shading-only; geometry deformation requires a new ready mesh revision.
     */
    record SetInstance<N>(InstanceId instance, SceneId scene, ReadyMesh<N> mesh,
                          GeometryTransform transform, int mask, ShaderData<N> instanceData,
                          PrimitiveLightMap primitiveLights) implements SceneEdit {
        public SetInstance(InstanceId instance, SceneId scene, ReadyMesh<N> mesh,
                           GeometryTransform transform, int mask, ShaderData<N> instanceData) {
            this(instance, scene, mesh, transform, mask, instanceData, PrimitiveLightMap.EMPTY);
        }

        public SetInstance {
            Objects.requireNonNull(instance);
            Objects.requireNonNull(scene);
            Objects.requireNonNull(mesh);
            Objects.requireNonNull(transform);
            Objects.requireNonNull(instanceData);
            Objects.requireNonNull(primitiveLights);
            if ((mask & ~255) != 0) throw new IllegalArgumentException("visibility mask must fit in eight bits");
        }
    }

    record DropInstance(InstanceId instance) implements SceneEdit {
        public DropInstance {
            Objects.requireNonNull(instance);
        }
    }

    /** Updates only the transform and eight-bit visibility mask of an existing placement. */
    record SetTransform(InstanceId instance, GeometryTransform transform, int mask) implements SceneEdit {
        public SetTransform {
            Objects.requireNonNull(instance);
            Objects.requireNonNull(transform);
            if ((mask & ~255) != 0) throw new IllegalArgumentException("visibility mask must fit in eight bits");
        }
    }

    record SetLight(LightId light, SceneId scene, LightDescriptor descriptor) implements SceneEdit {
        public SetLight {
            Objects.requireNonNull(light);
            Objects.requireNonNull(scene);
            Objects.requireNonNull(descriptor);
        }
    }

    record DropLight(LightId light) implements SceneEdit {
        public DropLight {
            Objects.requireNonNull(light);
        }
    }

    /** Selects this contribution's environment slot; removing its scope restores the previous surviving slot. */
    record SetEnvironment(SceneId scene, EnvironmentBinding<?> binding) implements SceneEdit {
        public SetEnvironment {
            Objects.requireNonNull(scene);
            Objects.requireNonNull(binding);
        }
    }
}
