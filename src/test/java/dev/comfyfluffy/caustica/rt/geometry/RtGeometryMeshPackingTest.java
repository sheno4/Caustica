package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtGeometryMeshPackingTest {
    @Test
    void resolvesEveryCoverageModeAndRoutesMaskedCoverageToAnyHitClass() {
        MaterialHandle material = MaterialHandle.of("test", "material");
        SceneMesh mesh = new SceneMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2, 0, 1, 2, 0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6],
                List.of(surface(material, SceneMesh.Coverage.OPAQUE), surface(material, SceneMesh.Coverage.CUTOUT),
                        surface(material, SceneMesh.Coverage.STOCHASTIC)));
        List<SceneMesh.Coverage> resolvedCoverage = new ArrayList<>();

        RtGeometryMeshPacking.PackedMesh packed = RtGeometryMeshPacking.pack(mesh, (reference, coverage) -> {
            resolvedCoverage.add(coverage);
            return new RtGeometryMaterialResolver.ResolvedMaterial(7 + coverage.ordinal(), 0);
        });

        assertEquals(List.of(SceneMesh.Coverage.OPAQUE, SceneMesh.Coverage.CUTOUT,
                SceneMesh.Coverage.STOCHASTIC), resolvedCoverage);
        assertArrayEquals(new int[]{1, 2, 0}, packed.classTriangles());
        assertEquals(7, Float.floatToRawIntBits(packed.primitives()[8]));
        assertEquals(8, Float.floatToRawIntBits(packed.primitives()[20]));
        assertEquals(9, Float.floatToRawIntBits(packed.primitives()[32]));
    }

    @Test
    void resolvesEqualMaterialCoveragePairsOnlyOnce() {
        MaterialHandle material = MaterialHandle.of("test", "shared");
        SceneMesh mesh = new SceneMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6],
                List.of(surface(material, SceneMesh.Coverage.OPAQUE),
                        surface(material, SceneMesh.Coverage.OPAQUE),
                        surface(material, SceneMesh.Coverage.CUTOUT),
                        surface(material, SceneMesh.Coverage.CUTOUT)));
        AtomicInteger resolutions = new AtomicInteger();

        RtGeometryMeshPacking.PackedMesh packed = RtGeometryMeshPacking.pack(mesh, (reference, coverage) -> {
            resolutions.incrementAndGet();
            return new RtGeometryMaterialResolver.ResolvedMaterial(7 + coverage.ordinal(), 0);
        });

        assertEquals(2, resolutions.get());
        assertEquals(7, Float.floatToRawIntBits(packed.primitives()[8]));
        assertEquals(7, Float.floatToRawIntBits(packed.primitives()[20]));
        assertEquals(8, Float.floatToRawIntBits(packed.primitives()[32]));
        assertEquals(8, Float.floatToRawIntBits(packed.primitives()[44]));
    }

    @Test
    void sharedSurfacePairHasTheSameHashContributionAndPackingAsEqualDistinctValues() {
        MaterialHandle material = MaterialHandle.of("test", "shared-pair");
        SceneMesh.TriangleSurface surface = surface(material, SceneMesh.Coverage.CUTOUT);
        SceneMesh shared = quadMesh(List.of(surface, surface));
        SceneMesh distinct = quadMesh(List.of(surface(material, SceneMesh.Coverage.CUTOUT),
                surface(material, SceneMesh.Coverage.CUTOUT)));
        RtGeometryMaterialResolver resolver = (reference, coverage) ->
                new RtGeometryMaterialResolver.ResolvedMaterial(7, 0);

        RtGeometryMeshPacking.PackedMesh sharedPacked = RtGeometryMeshPacking.pack(shared, resolver);
        RtGeometryMeshPacking.PackedMesh distinctPacked = RtGeometryMeshPacking.pack(distinct, resolver);

        assertEquals(surfaceHashContribution(shared.surfaces()), surfaceHashContribution(distinct.surfaces()));
        assertArrayEquals(sharedPacked.positions(), distinctPacked.positions());
        assertArrayEquals(sharedPacked.textureCoordinates(), distinctPacked.textureCoordinates());
        assertArrayEquals(sharedPacked.indices(), distinctPacked.indices());
        assertArrayEquals(sharedPacked.primitives(), distinctPacked.primitives());
        assertArrayEquals(sharedPacked.classTriangles(), distinctPacked.classTriangles());
        assertEquals(sharedPacked.flags(), distinctPacked.flags());
    }

    @Test
    void coverageClassChangeChangesManagerTopologyCompatibility() {
        MaterialHandle material = MaterialHandle.of("test", "material");
        RtGeometryMaterialResolver resolver = (reference, coverage) ->
                new RtGeometryMaterialResolver.ResolvedMaterial(7, 0);
        SceneMesh opaque = mesh(material, SceneMesh.Coverage.OPAQUE);
        SceneMesh cutout = mesh(material, SceneMesh.Coverage.CUTOUT);

        assertFalse(SceneMeshPacker.Topology.of(SceneMeshPacker.pack(opaque, resolver)).matches(
                SceneMeshPacker.Topology.of(SceneMeshPacker.pack(cutout, resolver))));
    }

    @Test
    void managerAcceptsTerrainStyleCornerUvs() {
        MaterialHandle material = MaterialHandle.of("test", "terrain");
        SceneMesh terrain = new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
                new int[]{0, 1, 2, 0, 2, 3}, SceneMesh.UvLayout.PER_TRIANGLE_CORNER,
                new float[]{0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1},
                List.of(surface(material, SceneMesh.Coverage.OPAQUE), surface(material, SceneMesh.Coverage.OPAQUE)));
        RtGeometryMaterialResolver resolver = (reference, coverage) ->
                new RtGeometryMaterialResolver.ResolvedMaterial(7, 0);

        SceneMeshPacker.PackedInput packed = assertDoesNotThrow(() -> SceneMeshPacker.pack(terrain, resolver));
        assertEquals(12, packed.textureCoordinates().length);
    }

    @Test
    void managerAcceptsPerVertexUvs() {
        MaterialHandle material = MaterialHandle.of("test", "entity");
        RtGeometryMaterialResolver resolver = (reference, coverage) ->
                new RtGeometryMaterialResolver.ResolvedMaterial(7, 0);

        SceneMeshPacker.PackedInput packed = assertDoesNotThrow(() -> SceneMeshPacker.pack(
                mesh(material, SceneMesh.Coverage.OPAQUE), resolver));
        assertEquals(6, packed.textureCoordinates().length);
    }

    @Test
    void preservesOptionalIndexedShadingAttributes() {
        MaterialHandle material = MaterialHandle.of("test", "vertex-attributes");
        float[] normals = {0, 0, 1, 0, 1, 0, 1, 0, 0};
        float[] colors = {1, 0, 0, 1, 0, 1, 0, 0.5f, 0, 0, 1, 0.25f};
        SceneMesh mesh = new SceneMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6], normals, colors,
                List.of(surface(material, SceneMesh.Coverage.OPAQUE)), java.util.Set.of());

        SceneMeshPacker.PackedInput packed = SceneMeshPacker.pack(mesh,
                (reference, coverage) -> new RtGeometryMaterialResolver.ResolvedMaterial(7, 0));

        assertArrayEquals(normals, packed.vertexNormals());
        assertArrayEquals(colors, packed.vertexColors());
        assertTrue((packed.semanticFlags() & RtGeometryAbi.FLAG_HAS_VERTEX_NORMALS) != 0);
        assertTrue((packed.semanticFlags() & RtGeometryAbi.FLAG_HAS_VERTEX_COLORS) != 0);
    }

    @Test
    void rejectsMismatchedIndexedShadingAttributes() {
        MaterialHandle material = MaterialHandle.of("test", "vertex-attributes");
        assertThrows(IllegalArgumentException.class, () -> new SceneMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6], new float[6], new float[0],
                List.of(surface(material, SceneMesh.Coverage.OPAQUE)), java.util.Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new SceneMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6], new float[0], new float[9],
                List.of(surface(material, SceneMesh.Coverage.OPAQUE)), java.util.Set.of()));
    }

    @Test
    void packsNeutralEmitterMembershipIntoOnlyTheSelectedPrimitive() {
        MaterialHandle material = MaterialHandle.of("test", "emitter");
        SceneMesh.TriangleSurface selected = surface(material, SceneMesh.Coverage.OPAQUE).withEmitterInLightScene();
        SceneMesh mesh = new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2, 0, 1, 2}, SceneMesh.UvLayout.PER_VERTEX, new float[6],
                List.of(selected, surface(material, SceneMesh.Coverage.OPAQUE)));

        RtGeometryMeshPacking.PackedMesh packed = RtGeometryMeshPacking.pack(mesh,
                (reference, coverage) -> new RtGeometryMaterialResolver.ResolvedMaterial(7, 0));

        assertEquals(1, Float.floatToRawIntBits(packed.primitives()[9]));
        assertEquals(0, Float.floatToRawIntBits(packed.primitives()[21]));
    }

    @Test
    void packsConservativeOmmRangeAndExplicitAbsenceIntoPrimitiveData() {
        MaterialHandle material = MaterialHandle.of("test", "omm");
        SceneMesh.TriangleSurface ranged = surface(material, SceneMesh.Coverage.CUTOUT)
                .withOpacityMicromapRange(new SceneMesh.OpacityMicromapRange(0.5001f, 0.749f));
        SceneMesh mesh = new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2, 0, 1, 2}, SceneMesh.UvLayout.PER_VERTEX, new float[6],
                List.of(ranged, surface(material, SceneMesh.Coverage.CUTOUT)));

        RtGeometryMeshPacking.PackedMesh packed = RtGeometryMeshPacking.pack(mesh,
                (reference, coverage) -> new RtGeometryMaterialResolver.ResolvedMaterial(7, 0));

        assertEquals(127 | (191 << 8), Float.floatToRawIntBits(packed.primitives()[11]));
        assertEquals(RtGeometryMeshPacking.OMM_RANGE_ABSENT,
                Float.floatToRawIntBits(packed.primitives()[23]));
    }

    @Test
    void packsProjectedSurfaceModifierSemanticOnlyWhenRequested() {
        MaterialHandle material = MaterialHandle.of("test", "terrain");
        SceneMesh defaultMesh = mesh(material, SceneMesh.Coverage.OPAQUE);
        SceneMesh projectedMesh = new SceneMesh(defaultMesh.positions(), defaultMesh.indices(), defaultMesh.uvLayout(),
                defaultMesh.textureCoordinates(), defaultMesh.surfaces(),
                java.util.Set.of(SceneMesh.Semantic.RECEIVES_PROJECTED_SURFACE_MODIFIERS));

        RtGeometryMeshPacking.PackedMesh defaultPacked = RtGeometryMeshPacking.pack(defaultMesh,
                (reference, coverage) -> new RtGeometryMaterialResolver.ResolvedMaterial(7, 0));
        RtGeometryMeshPacking.PackedMesh projectedPacked = RtGeometryMeshPacking.pack(projectedMesh,
                (reference, coverage) -> new RtGeometryMaterialResolver.ResolvedMaterial(7, 0));

        assertFalse((defaultPacked.flags() & RtGeometryAbi.FLAG_RECEIVES_PROJECTED_SURFACE_MODIFIERS) != 0);
        assertTrue((projectedPacked.flags() & RtGeometryAbi.FLAG_RECEIVES_PROJECTED_SURFACE_MODIFIERS) != 0);
    }

    private static SceneMesh mesh(MaterialHandle material, SceneMesh.Coverage coverage) {
        return new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[6], List.of(surface(material, coverage)));
    }

    private static SceneMesh quadMesh(List<SceneMesh.TriangleSurface> surfaces) {
        return new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
                new int[]{0, 1, 2, 0, 2, 3}, SceneMesh.UvLayout.PER_VERTEX, new float[8], surfaces);
    }

    private static long surfaceHashContribution(List<SceneMesh.TriangleSurface> surfaces) {
        long hash = 1469598103934665603L;
        for (SceneMesh.TriangleSurface surface : surfaces) {
            hash = (hash ^ surface.hashCode()) * 1099511628211L;
        }
        return hash;
    }

    private static SceneMesh.TriangleSurface surface(MaterialHandle material, SceneMesh.Coverage coverage) {
        SceneMesh.TriangleSurface base = SceneMesh.TriangleSurface.surface(material);
        return new SceneMesh.TriangleSurface(base.material(), coverage,
                Float.NaN, Float.NaN, Float.NaN, 0f, 1f, 1f, 1f);
    }
}
