package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftEntityMesh;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.support.ColorSpaces;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtEntityCaptureTest {
    private static final float[] X = {0f, 1f, 1f, 0f};
    private static final float[] Y = {0f, 0f, 1f, 1f};
    private static final float[] Z = {0f, 0f, 0f, 0f};
    private static final float[] U = {0f, 1f, 1f, 0f};
    private static final float[] V = {0f, 0f, 1f, 1f};

    @Test
    void formatsQuadDiagnosticsOnlyOnFailureAndDiscardsIncompleteVertices() {
        RtEntityCapture capture = new RtEntityCapture();
        int[] descriptions = {0};
        Object source = new Object() {
            @Override public String toString() {
                descriptions[0]++;
                return "custom source";
            }
        };
        capture.requireCompleteQuads(source);
        assertEquals(0, descriptions[0]);

        capture.addVertex(9, 9, 9, -1, 0, 0, 0, 0, 0, 0, 1);
        var failure = assertThrows(IllegalStateException.class, () -> capture.requireCompleteQuads(source));
        assertEquals("custom source left an incomplete quad (1 vertices)", failure.getMessage());
        assertEquals(1, descriptions[0]);

        for (int i = 0; i < 4; i++) {
            capture.addVertex(X[i], Y[i], Z[i], -1, U[i], V[i], 0, 0, 0, 0, 1);
        }
        capture.requireCompleteQuads(source);
        assertEquals(1, descriptions[0]);
        assertEquals(6, capture.idx.size());
        assertArrayEquals(new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}, capture.verts.toFloatArray());
    }

    @Test
    void particleCaptureDiscardsAnInterruptedVertexBeforeTheNextFrame() {
        RtEntityCapture capture = new RtEntityCapture();
        RtParticleCapture particles = new RtParticleCapture(capture);
        particles.beginCapture(100, 100, 100);
        particles.addVertex(9, 9, 9).setUv(0.5f, 0.5f).setColor(0);
        capture.reset();

        particles.beginCapture(10, 20, 30);
        for (int i = 0; i < 4; i++) {
            particles.addVertex(X[i], Y[i], Z[i]).setUv(U[i], V[i]).setLight(0);
        }
        particles.flush();
        particles.flush();

        capture.requireCompleteQuads("particles");
        assertEquals(6, capture.idx.size());
        assertArrayEquals(new float[]{10, 20, 30, 11, 20, 30, 11, 21, 30, 10, 21, 30},
                capture.verts.toFloatArray());
        assertArrayEquals(new float[]{0, 0, 1, 0, 1, 1, 0, 1}, capture.uvList.toFloatArray());
        for (int i = 0; i < 4; i++) assertEquals(1f, capture.colorList.getFloat(i * 4 + 3));
    }

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
    void decalOffsetsUseAuthoredOrGeometricNormalsWithoutRetainingThem() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.currentOrder = 2;
        capture.addDirectQuad(X, Y, Z, U, V, 0, 2, 0, -1);
        capture.addDirectQuad(X, Y, Z, U, V, 0, 0, 0, -1);
        float[] positions = capture.entityMesh().positions();
        for (int i = 0; i < 4; i++) {
            assertEquals(Y[i] + 0.0004f, positions[i * 3 + 1], 1.0e-7f);
            assertEquals(0.0004f, positions[(i + 4) * 3 + 2], 1.0e-7f);
        }
    }

    @Test
    void preservesPerVertexAndDrawAlpha() {
        RtEntityCapture capture = new RtEntityCapture();
        int[] colors = {0x20FF0000, 0x4000FF00, 0x800000FF, 0xFFFFFFFF};
        for (int i = 0; i < 4; i++) {
            capture.addVertex(X[i], Y[i], Z[i], colors[i], U[i], V[i], 0, 0, 0, 0, 1);
        }

        float[] captured = capture.entityMesh().vertexColors();
        assertEquals(0x20 / 255f, captured[3]);
        assertEquals(0x40 / 255f, captured[7]);
        assertEquals(0x80 / 255f, captured[11]);
        assertEquals(1f, captured[15]);
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
        assertEquals(1.25f, first.emission());
        float[] firstTint = ColorSpaces.srgbToAcesCg(128f / 255f, 64f / 255f, 32f / 255f);
        assertEquals(firstTint[0], mesh.vertexColors()[0]);
        assertEquals(firstTint[1], mesh.vertexColors()[1]);
        assertEquals(firstTint[2], mesh.vertexColors()[2]);
        assertEquals(1f, mesh.vertexColors()[3]);
        assertEquals(secondMaterial, second.material());
        assertEquals(MinecraftEntityMesh.Coverage.OPAQUE, second.coverage());
        assertEquals(2.5f, second.emission());
        float[] secondTint = ColorSpaces.srgbToAcesCg(16f / 255f, 32f / 255f, 64f / 255f);
        assertEquals(secondTint[0], mesh.vertexColors()[16]);
        assertEquals(secondTint[1], mesh.vertexColors()[17]);
        assertEquals(secondTint[2], mesh.vertexColors()[18]);
        assertEquals(1f, mesh.vertexColors()[19]);
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

        capture.colorList.set(3, 0.25f);
        RtEntities.MeshFingerprint alphaChanged = RtEntities.meshFingerprint(capture);
        assertNotEquals(uvChanged.contentHash(), alphaChanged.contentHash());
        assertEquals(uvChanged.topologyRevision(), alphaChanged.topologyRevision());

        capture.idx.set(0, 1);
        RtEntities.MeshFingerprint reordered = RtEntities.meshFingerprint(capture);
        assertNotEquals(alphaChanged.topologyRevision(), reordered.topologyRevision());
        assertEquals(reordered.topologyRevision(), capture.entityMesh(reordered.topologyRevision()).indexRevision());
    }

    @Test
    void surfaceFingerprintPreservesValuesAcrossSharedAndDistinctTriangleIdentities() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.currentMaterial = material("entity");
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);
        MinecraftEntityMesh.Triangle surface = capture.surfaces.getFirst();
        assertSame(surface, capture.surfaces.get(1));
        RtEntities.MeshFingerprint shared = RtEntities.meshFingerprint(capture);

        MinecraftEntityMesh.Triangle equalSurface = new MinecraftEntityMesh.Triangle(
                surface.material(), surface.coverage(), surface.emission());
        assertNotSame(surface, equalSurface);
        assertEquals(surface, equalSurface);
        capture.surfaces.set(1, equalSurface);
        assertEquals(shared, RtEntities.meshFingerprint(capture));

        capture.surfaces.set(1, new MinecraftEntityMesh.Triangle(
                surface.material(), surface.coverage(), surface.emission() + 1f));
        RtEntities.MeshFingerprint changed = RtEntities.meshFingerprint(capture);
        assertNotEquals(shared.contentHash(), changed.contentHash());
        assertEquals(shared.topologyRevision(), changed.topologyRevision());
    }

    @Test
    void particleCorrespondenceRequiresIdentityOrderAndPerParticleSpans() {
        Object first = new Object();
        Object second = new Object();
        var firstSpan = new RtEntities.ParticleMember(first, 4, 6, 1);
        var secondSpan = new RtEntities.ParticleMember(second, 8, 12, 2);

        assertTrue(RtEntities.sameParticleLayout(
                List.of(firstSpan, secondSpan), List.of(firstSpan, secondSpan)));
        assertFalse(RtEntities.sameParticleLayout(
                List.of(firstSpan, secondSpan), List.of(secondSpan, firstSpan)));
        assertFalse(RtEntities.sameParticleLayout(
                List.of(firstSpan), List.of(new RtEntities.ParticleMember(new Object(), 4, 6, 1))));
        assertFalse(RtEntities.sameParticleLayout(
                List.of(firstSpan), List.of(new RtEntities.ParticleMember(first, 8, 6, 1))));
        assertFalse(RtEntities.sameParticleLayout(
                List.of(firstSpan), List.of(new RtEntities.ParticleMember(first, 4, 12, 1))));
        assertFalse(RtEntities.sameParticleLayout(
                List.of(firstSpan), List.of(new RtEntities.ParticleMember(first, 4, 6, 2))));
        assertFalse(RtEntities.sameParticleLayout(List.of(firstSpan), List.of(firstSpan, secondSpan)));
    }

    @Test
    void bufferReuseCannotChangeAPublishedMesh() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.currentMaterial = material("first");
        capture.currentCoverage = MinecraftEntityMesh.Coverage.STOCHASTIC;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);
        MinecraftEntityMesh mesh = capture.entityMesh(31L);
        float[] positions = mesh.positions();
        int[] indices = mesh.indices();
        float[] uvs = mesh.uvs();
        float[] colors = mesh.vertexColors();
        var triangles = mesh.triangles();

        capture.reset();
        capture.currentMaterial = material("second");
        capture.addDirectQuad(new float[]{4, 5, 5, 4}, Y, Z,
                new float[]{.25f, .75f, .75f, .25f}, V, 0f, 1f, 0f, 0xFF123456);
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 1f, 0f, 0xFF654321);
        assertEquals(8, capture.entityMesh().vertexCount());

        assertEquals(4, mesh.vertexCount());
        assertEquals(2, mesh.triangleCount());
        assertEquals(12, mesh.positions().length);
        assertEquals(6, mesh.indices().length);
        assertEquals(8, mesh.uvs().length);
        assertEquals(16, mesh.vertexColors().length);
        assertEquals(31L, mesh.indexRevision());
        assertArrayEquals(positions, mesh.positions());
        assertArrayEquals(indices, mesh.indices());
        assertArrayEquals(uvs, mesh.uvs());
        assertArrayEquals(colors, mesh.vertexColors());
        assertEquals(triangles, mesh.triangles());
        assertEquals(material("first"), mesh.triangles().getFirst().material());
        assertEquals(MinecraftEntityMesh.Coverage.STOCHASTIC, mesh.triangles().getFirst().coverage());
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
        float[] colors = mesh.vertexColors();
        colors[3] = 0f;

        assertEquals(original, mesh.positions()[0]);
        assertEquals(0, mesh.indices()[0]);
        assertEquals(1f, mesh.vertexColors()[3]);
        assertEquals(23L, mesh.indexRevision());
    }

    private static MinecraftEntityMesh.Material material(String path) {
        return new MinecraftEntityMesh.Material(ResourceId.of("test", path), null,
                MinecraftEntityMesh.Program.MATERIAL);
    }
}
