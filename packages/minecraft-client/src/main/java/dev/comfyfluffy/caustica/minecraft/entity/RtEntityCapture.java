package dev.comfyfluffy.caustica.minecraft.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.support.ColorSpaces;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialIds;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link VertexConsumer} that records the posed entity geometry vanilla emits — exactly the same bulk
 * {@code addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)} that {@code ModelPart.Cube.compile} calls
 * (4 verts/quad → 2 triangles). {@link RtEntityCollector} drives it by calling
 * {@code model.renderToBuffer(pose, this, …)}.
 *
 * <p>Capture retains source-level positions, indexed UVs, and one neutral shading surface per triangle.
 */
public final class RtEntityCapture implements VertexConsumer {
    private static final int DEFAULT_VERTEX_CAPACITY = 1024;
    // Same magnitude as RtTerrain.QuadCapture.OFFSET (2e-4 blocks) — proven large enough to break a BVH
    // depth tie without a visible gap at terrain/entity scale.
    private static final float ORDER_OFFSET = 2.0e-4f;

    final FloatArrayList verts = new FloatArrayList(DEFAULT_VERTEX_CAPACITY * 3);   // 3 floats/vertex (capture-space position)
    final IntArrayList idx = new IntArrayList(indexCapacity(DEFAULT_VERTEX_CAPACITY)); // 3 indices/triangle
    final FloatArrayList uvList = new FloatArrayList(DEFAULT_VERTEX_CAPACITY * 2);  // 2 floats/vertex (entity-texture UV)
    final List<MinecraftEntityMesh.Triangle> surfaces = new ArrayList<>();
    // Reset to the diagnostic material so a producer path that omits material selection fails visibly.
    MinecraftEntityMesh.Material currentMaterial = fallbackMaterial();
    MinecraftEntityMesh.Coverage currentCoverage = MinecraftEntityMesh.Coverage.CUTOUT;
    // Decal-stacking rank for the current submission (0 = no offset). Set by the collector from
    // SubmitNodeCollector#order(int) — see emitQuad's coincident-layer push.
    int currentOrder;
    // When a model textures from an atlas sprite (block entities: chests/signs/beds via a Material),
    // its ModelPart UVs are 0..1 in a virtual texture and must be remapped into the sprite's atlas
    // region — the work vanilla's sprite-coordinate-expander VertexConsumer does, which we bypass.
    // Off for full-texture models (mobs, sprite == null) and for baked quads (already atlas-space).
    private boolean uvRemap;
    private float uvU0, uvV0, uvDU, uvDV;

    private int n; // quad vertex accumulator (0..3)
    private final float[] qx = new float[4], qy = new float[4], qz = new float[4];
    private final float[] qu = new float[4], qv = new float[4];
    private final float[] qnx = new float[4], qny = new float[4], qnz = new float[4];
    private final int[] qcol = new int[4];
    private final Vector3f scratch = new Vector3f(); // baked-quad position transform scratch

    /** Clear all accumulators for a fresh entity capture. */
    public void reset() {
        verts.clear();
        idx.clear();
        uvList.clear();
        surfaces.clear();
        n = 0;
        currentMaterial = fallbackMaterial();
        currentCoverage = MinecraftEntityMesh.Coverage.CUTOUT;
        currentOrder = 0;
        uvRemap = false;
    }

    private void ensureVertexCapacity(int vertexCount) {
        if (vertexCount <= 0) {
            return;
        }
        verts.ensureCapacity(vertexCount * 3);
        idx.ensureCapacity(indexCapacity(vertexCount));
        uvList.ensureCapacity(vertexCount * 2);
    }

    /** Reserve room for an upcoming direct-model submission without changing any logical sizes. */
    void ensureAdditionalVertexCapacity(int additionalVertices) {
        if (additionalVertices > 0) {
            ensureVertexCapacity(verts.size() / 3 + additionalVertices);
        }
    }

    private static int indexCapacity(int vertexCount) {
        int quadCount = (vertexCount + 3) / 4;
        return quadCount * 6;
    }

    /** Remap subsequent {@link #addVertex} (ModelPart) UVs from 0..1 into a sprite's atlas region. */
    public void setUvRemap(float u0, float v0, float u1, float v1) {
        uvRemap = true;
        uvU0 = u0;
        uvV0 = v0;
        uvDU = u1 - u0;
        uvDV = v1 - v0;
    }

    /** Use ModelPart UVs as-is (full-texture models). */
    public void clearUvRemap() {
        uvRemap = false;
    }

    public boolean isEmpty() {
        return idx.isEmpty();
    }

    MinecraftEntityMesh entityMesh() {
        return entityMesh(0L);
    }

    MinecraftEntityMesh entityMesh(long indexRevision) {
        return new MinecraftEntityMesh(java.util.Arrays.copyOf(verts.elements(), verts.size()),
                java.util.Arrays.copyOf(idx.elements(), idx.size()),
                java.util.Arrays.copyOf(uvList.elements(), uvList.size()), surfaces, indexRevision);
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v,
                          int overlay, int light, float nx, float ny, float nz) {
        if (uvRemap) { // ModelPart 0..1 UV → sprite's atlas region (block-entity Material sprites)
            u = uvU0 + u * uvDU;
            v = uvV0 + v * uvDV;
        }
        qx[n] = x; qy[n] = y; qz[n] = z;
        qu[n] = u; qv[n] = v;
        qnx[n] = nx; qny[n] = ny; qnz[n] = nz;
        qcol[n] = color;
        if (++n == 4) {
            emitQuad();
            n = 0;
        }
    }

    /**
     * Capture a {@link BakedQuad} (held/dropped items via {@code submitItem}, falling blocks via {@code
     * submitBlockModel}) — its 4 positions transformed by {@code pose}, atlas UV from {@code packedUV},
     * a flat {@code color} tint. These quads carry no authored normal, so emitQuad computes a geometric
     * one. Their source material is assigned by the collector before the quad is appended.
     */
    public void addBakedQuad(Matrix4f pose, BakedQuad quad, int color) {
        for (int i = 0; i < 4; i++) {
            Vector3fc p = quad.position(i);
            pose.transformPosition(p.x(), p.y(), p.z(), scratch);
            long uv = quad.packedUV(i);
            qx[n] = scratch.x; qy[n] = scratch.y; qz[n] = scratch.z;
            qu[n] = Float.intBitsToFloat((int) (uv >>> 32));
            qv[n] = Float.intBitsToFloat((int) uv);
            qnx[n] = 0f; qny[n] = 0f; qnz[n] = 0f; // no authored normal → emitQuad falls back to geometric
            qcol[n] = color;
            if (++n == 4) {
                emitQuad();
                n = 0;
            }
        }
    }

    private void emitQuad() {
        appendQuad(qx, qy, qz, null, qu, qv, qnx[0], qny[0], qnz[0], qcol[0], false, 0f);
    }

    /**
     * Append one already-transformed model quad without routing its four vertices through the
     * {@link VertexConsumer} accumulator. The direct cuboid path supplies the same polygon order,
     * authored normal, UVs and flat submission colour as {@code ModelPart.Cube.compile}.
     */
    void addDirectQuad(float[] x, float[] y, float[] z, float[] u, float[] v,
                       float nx, float ny, float nz, int color) {
        addDirectQuad(x, y, z, u, v, nx, ny, nz, color, 0f);
    }

    /** Append a direct quad with an explicit per-primitive emission strength. */
    void addDirectQuad(float[] x, float[] y, float[] z, float[] u, float[] v,
                       float nx, float ny, float nz, int color, float emission) {
        appendQuad(x, y, z, null, u, v, nx, ny, nz, color, uvRemap, emission);
    }

    /** Fail fast before a later submission can accidentally complete a malformed custom-geometry quad. */
    void requireCompleteQuads(String label) {
        if (n != 0) {
            int incomplete = n;
            n = 0;
            throw new IllegalStateException(label + " left an incomplete quad (" + incomplete + " vertices)");
        }
    }

    /** Append a face whose positions reference a transformed eight-corner cube template. */
    void addIndexedDirectQuad(float[] x, float[] y, float[] z, int[] corners, float[] u, float[] v,
                              float nx, float ny, float nz, int color) {
        appendQuad(x, y, z, corners, u, v, nx, ny, nz, color, uvRemap, 0f);
    }

    private void appendQuad(float[] x, float[] y, float[] z, int[] corners, float[] u, float[] v,
                            float nx, float ny, float nz, int color, boolean remapUv, float emission) {
        // Authored model normal (pose-transformed by compile); planar quad, so vertex 0's normal is the
        // face normal. Baked quads (items/blocks) pass no normal → fall back to a geometric one from the
        // quad edges. The closest-hit flips it toward the viewer, as for terrain. Computed BEFORE the
        // positions are staged so a same-order offset (below) can push along it.
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len <= 1.0e-6f) {
            int p0 = positionIndex(corners, 0);
            int p1 = positionIndex(corners, 1);
            int p2 = positionIndex(corners, 2);
            float ex1 = x[p1] - x[p0], ey1 = y[p1] - y[p0], ez1 = z[p1] - z[p0];
            float ex2 = x[p2] - x[p0], ey2 = y[p2] - y[p0], ez2 = z[p2] - z[p0];
            nx = ey1 * ez2 - ez1 * ey2;
            ny = ez1 * ex2 - ex1 * ez2;
            nz = ex1 * ey2 - ey1 * ex2;
            len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (len > 1.0e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        // Stacked decal layers (banner/shield patterns: base cloth + per-pattern cutout layers, all the
        // SAME coplanar mesh submitted repeatedly via SubmitNodeCollector#order) tie exactly in the BVH —
        // push each later layer outward along the face normal by rank, same fix as terrain's coincident
        // grass-overlay resolution (RtTerrain.QuadCapture), so any-hit cutout lets the ray fall through a
        // discarded pattern texel to the layer behind instead of a random BVH pick.
        boolean offset = currentOrder != 0 && len > 1.0e-6f;
        float off = offset ? ORDER_OFFSET * currentOrder : 0f;

        int base = verts.size() / 3;
        for (int i = 0; i < 4; i++) {
            int p = positionIndex(corners, i);
            verts.add(offset ? x[p] + nx * off : x[p]);
            verts.add(offset ? y[p] + ny * off : y[p]);
            verts.add(offset ? z[p] + nz * off : z[p]);
            uvList.add(remapUv ? uvU0 + u[i] * uvDU : u[i]);
            uvList.add(remapUv ? uvV0 + v[i] * uvDV : v[i]);
        }
        idx.add(base);
        idx.add(base + 1);
        idx.add(base + 2);
        idx.add(base);
        idx.add(base + 2);
        idx.add(base + 3);
        // Minecraft submission colours are encoded sRGB; the shader ABI consumes scene-linear ACEScg.
        int c = color;
        float[] tint = ColorSpaces.srgbToAcesCg(
                ((c >> 16) & 0xFF) * (1f / 255f),
                ((c >> 8) & 0xFF) * (1f / 255f),
                (c & 0xFF) * (1f / 255f));
        MinecraftEntityMesh.Triangle surface = new MinecraftEntityMesh.Triangle(
                currentMaterial, currentCoverage, nx, ny, nz, emission, tint[0], tint[1], tint[2]);
        surfaces.add(surface);
        surfaces.add(surface);
    }

    private static int positionIndex(int[] corners, int vertex) {
        return corners == null ? vertex : corners[vertex];
    }

    private static MinecraftEntityMesh.Material fallbackMaterial() {
        return new MinecraftEntityMesh.Material(MinecraftMaterialIds.PARTICLE_BILLBOARD, null,
                MinecraftEntityMesh.Program.MATERIAL);
    }

    // Unused VertexConsumer surface — ModelPart.Cube.compile only calls the bulk addVertex above.
    @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
    @Override public VertexConsumer setColor(int color) { return this; }
    @Override public VertexConsumer setUv(float u, float v) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }
}
