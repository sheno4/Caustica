package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;

import java.util.HashMap;
import java.util.Map;

/** Converts neutral scene meshes into the renderer's three SBT-class streams. */
final class RtGeometryMeshPacking {
    private static final int PRIMITIVE_FLOATS = 12;
    private static final int COVERAGE_MODES = SceneMesh.Coverage.values().length;

    private RtGeometryMeshPacking() {
    }

    static PackedMesh pack(SceneMesh mesh, RtGeometryMaterialResolver resolver) {
        float[] positions = mesh.positions();
        int[] sourceIndices = mesh.indices();
        float[] sourceUvs = mesh.textureCoordinates();
        int triangleCount = mesh.triangleCount();
        int[] indices = new int[sourceIndices.length];
        float[] uvs = mesh.uvLayout() == SceneMesh.UvLayout.PER_VERTEX ? sourceUvs : new float[sourceUvs.length];
        float[] primitives = new float[triangleCount * PRIMITIVE_FLOATS];
        int[] classes = new int[RtAccel.SBT_CLASSES];
        int[] materialIds = new int[triangleCount];
        int[] surfaceClasses = new int[triangleCount];
        Map<SceneMesh.MaterialReference, RtGeometryMaterialResolver.ResolvedMaterial[]> resolvedMaterials =
                new HashMap<>();
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            SceneMesh.TriangleSurface surface = mesh.surfaces().get(triangle);
            RtGeometryMaterialResolver.ResolvedMaterial[] coverageVariants = resolvedMaterials.computeIfAbsent(
                    surface.material(), ignored -> new RtGeometryMaterialResolver.ResolvedMaterial[COVERAGE_MODES]);
            int coverage = surface.coverage().ordinal();
            RtGeometryMaterialResolver.ResolvedMaterial resolved = coverageVariants[coverage];
            if (resolved == null) {
                resolved = resolver.resolve(surface.material(), surface.coverage());
                coverageVariants[coverage] = resolved;
            }
            materialIds[triangle] = resolved.bindingId();
            surfaceClasses[triangle] = surface.coverage() == SceneMesh.Coverage.OPAQUE
                    ? resolved.sbtClass() : RtAccel.CLASS_MASKED;
        }
        int output = 0;
        for (int targetClass = 0; targetClass < classes.length; targetClass++) {
            for (int triangle = 0; triangle < triangleCount; triangle++) {
                SceneMesh.TriangleSurface surface = mesh.surfaces().get(triangle);
                if (surfaceClasses[triangle] != targetClass) continue;
                int inputIndex = triangle * 3;
                int outputIndex = output * 3;
                indices[outputIndex] = sourceIndices[inputIndex];
                indices[outputIndex + 1] = sourceIndices[inputIndex + 1];
                indices[outputIndex + 2] = sourceIndices[inputIndex + 2];
                if (mesh.uvLayout() == SceneMesh.UvLayout.PER_TRIANGLE_CORNER) {
                    System.arraycopy(sourceUvs, triangle * 6, uvs, output * 6, 6);
                }
                writeSurfacePrimitive(primitives, output * PRIMITIVE_FLOATS, positions, sourceIndices, inputIndex,
                        surface, materialIds[triangle]);
                output++;
                classes[targetClass]++;
            }
        }
        int flags = mesh.uvLayout() == SceneMesh.UvLayout.PER_TRIANGLE_CORNER
                ? RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES
                : RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES;
        if (mesh.semantics().contains(SceneMesh.Semantic.RECEIVES_PROJECTED_SURFACE_MODIFIERS)) {
            flags |= RtGeometryAbi.FLAG_RECEIVES_PROJECTED_SURFACE_MODIFIERS;
        }
        return new PackedMesh(positions, uvs, indices, primitives, classes, flags);
    }

    private static void writeSurfacePrimitive(float[] output, int offset, float[] positions, int[] indices,
                                              int indexOffset, SceneMesh.TriangleSurface surface, int materialId) {
        float nx = surface.normalX();
        float ny = surface.normalY();
        float nz = surface.normalZ();
        if (Float.isNaN(nx)) {
            int a = indices[indexOffset] * 3;
            int b = indices[indexOffset + 1] * 3;
            int c = indices[indexOffset + 2] * 3;
            float abx = positions[b] - positions[a];
            float aby = positions[b + 1] - positions[a + 1];
            float abz = positions[b + 2] - positions[a + 2];
            float acx = positions[c] - positions[a];
            float acy = positions[c + 1] - positions[a + 1];
            float acz = positions[c + 2] - positions[a + 2];
            nx = aby * acz - abz * acy;
            ny = abz * acx - abx * acz;
            nz = abx * acy - aby * acx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length <= 1.0e-8f) throw new IllegalArgumentException("scene mesh contains a degenerate triangle");
            nx /= length;
            ny /= length;
            nz /= length;
        }
        output[offset] = nx;
        output[offset + 1] = ny;
        output[offset + 2] = nz;
        output[offset + 3] = surface.emission();
        output[offset + 4] = surface.tintR();
        output[offset + 5] = surface.tintG();
        output[offset + 6] = surface.tintB();
        output[offset + 7] = 0f;
        output[offset + 8] = Float.intBitsToFloat(materialId);
        output[offset + 9] = Float.intBitsToFloat(surface.emitterInLightScene() ? 1 : 0);
        output[offset + 10] = 0f;
        output[offset + 11] = 0f;
    }

    record PackedMesh(float[] positions, float[] textureCoordinates, int[] indices,
                      float[] primitives, int[] classTriangles, int flags) {
    }
}
