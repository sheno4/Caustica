package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.api.ColorSpaces;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        capture.currentMaterial = new SceneMesh.NamedMaterial(MaterialHandle.of("test", "entity"));
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
        SceneMesh.MaterialReference firstMaterial = new SceneMesh.NamedMaterial(MaterialHandle.of("test", "first"));
        SceneMesh.MaterialReference secondMaterial = new SceneMesh.NamedMaterial(MaterialHandle.of("test", "second"));
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
        float[] firstTint = ColorSpaces.srgbToAcesCg(128f / 255f, 64f / 255f, 32f / 255f);
        assertEquals(firstTint[0], first.tintR());
        assertEquals(firstTint[1], first.tintG());
        assertEquals(firstTint[2], first.tintB());
        assertEquals(secondMaterial, second.material());
        assertEquals(SceneMesh.Coverage.OPAQUE, second.coverage());
        assertEquals(0f, second.normalX());
        assertEquals(1f, second.normalY());
        assertEquals(0f, second.normalZ());
        assertEquals(2.5f, second.emission());
        float[] secondTint = ColorSpaces.srgbToAcesCg(16f / 255f, 32f / 255f, 64f / 255f);
        assertEquals(secondTint[0], second.tintR());
        assertEquals(secondTint[1], second.tintG());
        assertEquals(secondTint[2], second.tintB());
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

    @Test
    void topologyFingerprintRetainsAttributeChangesButRejectsIndexReordering() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);

        RtEntities.MeshFingerprint initial = RtEntities.meshFingerprint(capture);
        assertNull(capture.sceneMesh().topologyRevision());

        capture.verts.set(0, 0.25f);
        RtEntities.MeshFingerprint deformed = RtEntities.meshFingerprint(capture);
        assertNotEquals(initial.contentHash(), deformed.contentHash());
        assertEquals(initial.topologyRevision(), deformed.topologyRevision());

        capture.uvList.set(0, 0.25f);
        RtEntities.MeshFingerprint uvChanged = RtEntities.meshFingerprint(capture);
        assertNotEquals(deformed.contentHash(), uvChanged.contentHash());
        assertEquals(deformed.topologyRevision(), uvChanged.topologyRevision());

        capture.idx.set(0, 1);
        RtEntities.MeshFingerprint reordered = RtEntities.meshFingerprint(capture);
        assertNotEquals(uvChanged.topologyRevision(), reordered.topologyRevision());
        assertEquals(reordered.topologyRevision(), capture.sceneMesh(reordered.topologyRevision()).topologyRevision());
    }
}
