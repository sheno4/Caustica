package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;

import java.util.List;

/** Immutable CPU geometry and authored node placements for one glTF resource epoch. */
public record GltfScene(List<Primitive> primitives, List<Placement> placements) {
    public GltfScene {
        primitives = List.copyOf(primitives);
        placements = List.copyOf(placements);
    }

    public record Primitive(float[] positions, int[] indices, float red, float green, float blue, float alpha,
                            float roughness, float metallic, boolean cutout, float alphaCutoff) {
        public Primitive {
            positions = positions.clone();
            indices = indices.clone();
            if (positions.length < 9 || positions.length % 3 != 0) {
                throw new IllegalArgumentException("glTF primitive positions must contain float3 vertices");
            }
            if (indices.length == 0 || indices.length % 3 != 0) {
                throw new IllegalArgumentException("glTF primitive indices must contain triangles");
            }
        }

        @Override public float[] positions() { return positions.clone(); }
        @Override public int[] indices() { return indices.clone(); }
    }

    /** A primitive instance with its authored glTF node-world matrix in column-major order. */
    public record Placement(int primitive, float[] nodeWorldMatrix) {
        public Placement {
            nodeWorldMatrix = nodeWorldMatrix.clone();
            if (nodeWorldMatrix.length != 16) throw new IllegalArgumentException("node matrix must be 4x4");
        }

        @Override public float[] nodeWorldMatrix() { return nodeWorldMatrix.clone(); }

        public GeometryTransform at(double anchorX, double anchorY, double anchorZ) {
            return new GeometryTransform(
                    nodeWorldMatrix[0], nodeWorldMatrix[4], nodeWorldMatrix[8],
                    nodeWorldMatrix[1], nodeWorldMatrix[5], nodeWorldMatrix[9],
                    nodeWorldMatrix[2], nodeWorldMatrix[6], nodeWorldMatrix[10],
                    anchorX + (double) nodeWorldMatrix[12],
                    anchorY + (double) nodeWorldMatrix[13],
                    anchorZ + (double) nodeWorldMatrix[14]);
        }
    }
}
