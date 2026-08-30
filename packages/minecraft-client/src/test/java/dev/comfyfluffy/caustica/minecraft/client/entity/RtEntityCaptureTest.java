package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftEntityMesh;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.support.ColorSpaces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        capture.currentMaterial = material("entity");
        capture.currentCoverage = MinecraftEntityMesh.Coverage.STOCHASTIC;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);

        MinecraftEntityMesh mesh = capture.entityMesh();

        assertEquals(4, mesh.vertexCount());
        assertEquals(2, mesh.triangleCount());
        assertEquals(2, mesh.triangles().size());
        assertEquals(MinecraftEntityMesh.Coverage.STOCHASTIC, mesh.triangles().getFirst().coverage());
        assertSame(mesh.triangles().getFirst(), mesh.triangles().get(1));
    }

    @Test
    void adjacentQuadsShareOnlyTheirOwnSurfaceValue() {
        RtEntityCapture capture = new RtEntityCapture();
        MinecraftEntityMesh.Material firstMaterial = material("first");
        MinecraftEntityMesh.Material secondMaterial = material("second");
        capture.currentMaterial = firstMaterial;
        capture.currentCoverage = MinecraftEntityMesh.Coverage.STOCHASTIC;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, 0xFF804020, 1.25f);
        capture.currentMaterial = secondMaterial;
        capture.currentCoverage = MinecraftEntityMesh.Coverage.OPAQUE;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 1f, 0f, 0xFF102040, 2.5f);

        MinecraftEntityMesh mesh = capture.entityMesh();
        MinecraftEntityMesh.Triangle first = mesh.triangles().get(0);
        MinecraftEntityMesh.Triangle second = mesh.triangles().get(2);
        assertSame(first, mesh.triangles().get(1));
        assertSame(second, mesh.triangles().get(3));
        assertNotSame(first, second);
        assertEquals(firstMaterial, first.material());
        assertEquals(MinecraftEntityMesh.Coverage.STOCHASTIC, first.coverage());
        assertEquals(0f, first.normalX());
        assertEquals(0f, first.normalY());
        assertEquals(1f, first.normalZ());
        assertEquals(1.25f, first.emission());
        float[] firstTint = ColorSpaces.srgbToAcesCg(128f / 255f, 64f / 255f, 32f / 255f);
        assertEquals(firstTint[0], first.tintR());
        assertEquals(firstTint[1], first.tintG());
        assertEquals(firstTint[2], first.tintB());
        assertEquals(secondMaterial, second.material());
        assertEquals(MinecraftEntityMesh.Coverage.OPAQUE, second.coverage());
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
        MinecraftEntityMesh.Triangle retired = capture.surfaces.getFirst();
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
        assertEquals(0L, capture.entityMesh().indexRevision());

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
        assertEquals(reordered.topologyRevision(), capture.entityMesh(reordered.topologyRevision()).indexRevision());
    }

    @Test
    void capturedCpuMeshDoesNotExposeMutableStreams() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);
        MinecraftEntityMesh mesh = capture.entityMesh(23L);

        float original = mesh.positions()[0];
        float[] positions = mesh.positions();
        positions[0] = 99f;
        int[] indices = mesh.indices();
        indices[0] = 3;

        assertEquals(original, mesh.positions()[0]);
        assertEquals(0, mesh.indices()[0]);
        assertEquals(23L, mesh.indexRevision());
    }

    private static MinecraftEntityMesh.Material material(String path) {
        return new MinecraftEntityMesh.Material(ResourceId.of("test", path), null,
                MinecraftEntityMesh.Program.MATERIAL);
    }
}
