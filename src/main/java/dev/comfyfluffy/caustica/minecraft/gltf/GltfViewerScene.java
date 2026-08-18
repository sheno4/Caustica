package dev.comfyfluffy.caustica.minecraft.gltf;

import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/** Immutable renderer-facing view of one authored glTF scene. */
record GltfViewerScene(List<Resident> residents, List<Placement> placements,
                       List<MaterialDefinition> materials, List<Texture> textures) {
    GltfViewerScene {
        residents = List.copyOf(residents);
        placements = List.copyOf(placements);
        materials = List.copyOf(materials);
        textures = List.copyOf(textures);
    }

    record Resident(SceneGeometryKey key, SceneMesh mesh) {
        Resident {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(mesh, "mesh");
        }
    }

    /** A primitive instance with its authored glTF node-world matrix in column-major order. */
    record Placement(SceneGeometryKey resident, float[] nodeWorldMatrix) {
        Placement {
            Objects.requireNonNull(resident, "resident");
            nodeWorldMatrix = nodeWorldMatrix.clone();
            if (nodeWorldMatrix.length != 16) {
                throw new IllegalArgumentException("glTF node matrix must have 16 elements");
            }
        }

        @Override
        public float[] nodeWorldMatrix() {
            return nodeWorldMatrix.clone();
        }

        GeometryTransform at(BlockPos anchor) {
            return new GeometryTransform(
                    nodeWorldMatrix[0], nodeWorldMatrix[4], nodeWorldMatrix[8],
                    nodeWorldMatrix[1], nodeWorldMatrix[5], nodeWorldMatrix[9],
                    nodeWorldMatrix[2], nodeWorldMatrix[6], nodeWorldMatrix[10],
                    anchor.getX() + (double) nodeWorldMatrix[12],
                    anchor.getY() + (double) nodeWorldMatrix[13],
                    anchor.getZ() + (double) nodeWorldMatrix[14]);
        }
    }

    record Texture(SceneMesh.TextureReference reference, CpuTextureResource content) {
        Texture {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(content, "content");
        }
    }
}
