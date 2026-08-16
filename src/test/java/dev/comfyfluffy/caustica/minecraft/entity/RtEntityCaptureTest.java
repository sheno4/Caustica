package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RtEntityCaptureTest {
    private static final float[] X = {0f, 1f, 1f, 0f};
    private static final float[] Y = {0f, 0f, 1f, 1f};
    private static final float[] Z = {0f, 0f, 0f, 0f};
    private static final float[] U = {0f, 1f, 1f, 0f};
    private static final float[] V = {0f, 0f, 1f, 1f};

    @Test
    void capturesOneNeutralSurfaceForEachTriangle() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.currentMaterial = new SceneMesh.StandaloneMaterial(ResourceId.of("test", "entity"));
        capture.currentCoverage = SceneMesh.Coverage.STOCHASTIC;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);

        SceneMesh mesh = capture.sceneMesh();

        assertEquals(4, mesh.vertexCount());
        assertEquals(2, mesh.triangleCount());
        assertEquals(2, mesh.surfaces().size());
        assertEquals(SceneMesh.Coverage.STOCHASTIC, mesh.surfaces().getFirst().coverage());
        assertSame(mesh.surfaces().getFirst(), mesh.surfaces().get(1));
    }

    @Test
    void adjacentQuadsShareOnlyTheirOwnSurfaceValue() {
        RtEntityCapture capture = new RtEntityCapture();
        SceneMesh.MaterialReference firstMaterial = new SceneMesh.StandaloneMaterial(
                ResourceId.of("test", "first"));
        SceneMesh.MaterialReference secondMaterial = new SceneMesh.StandaloneMaterial(
                ResourceId.of("test", "second"));
        capture.currentMaterial = firstMaterial;
        capture.currentCoverage = SceneMesh.Coverage.STOCHASTIC;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, 0xFF804020, 1.25f);
        capture.currentMaterial = secondMaterial;
        capture.currentCoverage = SceneMesh.Coverage.OPAQUE;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 1f, 0f, 0xFF102040, 2.5f);

        SceneMesh mesh = capture.sceneMesh();
        SceneMesh.TriangleSurface first = mesh.surfaces().get(0);
        SceneMesh.TriangleSurface second = mesh.surfaces().get(2);
        assertSame(first, mesh.surfaces().get(1));
        assertSame(second, mesh.surfaces().get(3));
        assertNotSame(first, second);
        assertEquals(firstMaterial, first.material());
        assertEquals(SceneMesh.Coverage.STOCHASTIC, first.coverage());
        assertEquals(0f, first.normalX());
        assertEquals(0f, first.normalY());
        assertEquals(1f, first.normalZ());
        assertEquals(1.25f, first.emission());
        assertEquals(128f / 255f, first.tintR());
        assertEquals(64f / 255f, first.tintG());
        assertEquals(32f / 255f, first.tintB());
        assertEquals(secondMaterial, second.material());
        assertEquals(SceneMesh.Coverage.OPAQUE, second.coverage());
        assertEquals(0f, second.normalX());
        assertEquals(1f, second.normalY());
        assertEquals(0f, second.normalZ());
        assertEquals(2.5f, second.emission());
        assertEquals(16f / 255f, second.tintR());
        assertEquals(32f / 255f, second.tintG());
        assertEquals(64f / 255f, second.tintB());
    }

    @Test
    void resetDoesNotReuseTheRetiredSurfaceObject() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);
        SceneMesh.TriangleSurface retired = capture.surfaces.getFirst();
        capture.reset();
        assertEquals(0, capture.surfaces.size());

        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);
        assertSame(capture.surfaces.getFirst(), capture.surfaces.get(1));
        assertNotSame(retired, capture.surfaces.getFirst());
    }
}
