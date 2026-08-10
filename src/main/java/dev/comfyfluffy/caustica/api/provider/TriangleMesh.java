package dev.comfyfluffy.caustica.api.provider;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable indexed triangle mesh retained by a {@link SceneGeometrySink}. */
public final class TriangleMesh {
    private final float[] positions;
    private final float[] texCoords;
    private final int[] indices;
    private final List<MaterialRange> materials;

    public TriangleMesh(float[] positions, float[] texCoords, int[] indices, List<MaterialRange> materials) {
        this.positions = positions.clone();
        this.texCoords = texCoords.clone();
        this.indices = indices.clone();
        this.materials = List.copyOf(materials);
        validate();
    }

    public float[] positions() {
        return positions.clone();
    }

    public float[] texCoords() {
        return texCoords.clone();
    }

    public int[] indices() {
        return indices.clone();
    }

    public List<MaterialRange> materials() {
        return materials;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    private void validate() {
        if (positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must contain complete xyz vertices");
        }
        requireFinite(positions, "positions");
        if (texCoords.length != vertexCount() * 2) {
            throw new IllegalArgumentException("texCoords must contain one uv pair per vertex");
        }
        requireFinite(texCoords, "texCoords");
        if (indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("indices must contain complete triangles");
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount()) {
                throw new IllegalArgumentException("triangle index outside vertex array: " + index);
            }
        }
        int nextTriangle = 0;
        for (MaterialRange range : materials) {
            Objects.requireNonNull(range, "material range");
            if (range.firstTriangle() != nextTriangle) {
                throw new IllegalArgumentException("material ranges must be contiguous and ordered");
            }
            nextTriangle = Math.addExact(nextTriangle, range.triangleCount());
        }
        if (nextTriangle != triangleCount()) {
            throw new IllegalArgumentException("material ranges must cover every triangle");
        }
    }

    private static void requireFinite(float[] values, String label) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(label + " must be finite");
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TriangleMesh mesh
                && Arrays.equals(positions, mesh.positions)
                && Arrays.equals(texCoords, mesh.texCoords)
                && Arrays.equals(indices, mesh.indices)
                && materials.equals(mesh.materials);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(positions);
        result = 31 * result + Arrays.hashCode(texCoords);
        result = 31 * result + Arrays.hashCode(indices);
        return 31 * result + materials.hashCode();
    }

    /** A contiguous triangle span using one material. */
    public record MaterialRange(int firstTriangle, int triangleCount, MaterialHandle material) {
        public MaterialRange {
            if (firstTriangle < 0 || triangleCount <= 0) {
                throw new IllegalArgumentException("material range must contain triangles");
            }
            Objects.requireNonNull(material, "material");
        }
    }
}
