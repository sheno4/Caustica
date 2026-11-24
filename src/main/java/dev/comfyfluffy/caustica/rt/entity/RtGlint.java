package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.MatrixUtil;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Captures the animated texture coordinates of a glint overlay on its underlying geometry. */
final class RtGlint implements VertexConsumer {
    static final int PRIMITIVE_BIT = 0x80000000;

    private final int[] packedUv = new int[4];
    private final Vector3f uv = new Vector3f();
    private Matrix4f textureTransform;
    private SheetedDecalTextureGenerator projection;

    void begin(TextureTransform transform, PoseStack.Pose pose, ItemDisplayContext context) {
        textureTransform = transform.createMatrix();
        if (pose != null) {
            PoseStack.Pose decalPose = pose.copy();
            if (context.firstPerson()) MatrixUtil.mulComponentWise(decalPose.pose(), 0.75f);
            projection = new SheetedDecalTextureGenerator(this, decalPose, 1f / 128f);
        }
    }

    void end() {
        textureTransform = null;
        projection = null;
    }

    boolean active() {
        return textureTransform != null;
    }

    void captureQuad(float[] x, float[] y, float[] z, int[] corners,
                     float[] u, float[] v, float nx, float ny, float nz) {
        float wrapU = 0f;
        float wrapV = 0f;
        for (int i = 0; i < 4; i++) {
            uv.set(u[i], v[i], 0f);
            if (projection != null) {
                int p = corners == null ? i : corners[i];
                projection.addVertex(x[p], y[p], z[p]).setNormal(nx, ny, nz);
            }
            textureTransform.transformPosition(uv);
            if (i == 0) {
                // One integer offset for the whole quad preserves interpolation across wrap seams.
                wrapU = (float) Math.floor(uv.x);
                wrapV = (float) Math.floor(uv.y);
            }
            int packedU = Float.floatToFloat16(uv.x - wrapU) & 0xffff;
            int packedV = Float.floatToFloat16(uv.y - wrapV) & 0xffff;
            packedUv[i] = packedU | (packedV << 16);
        }
    }

    float packedUv(int corner) {
        return Float.intBitsToFloat(packedUv[corner]);
    }

    @Override public VertexConsumer setUv(float u, float v) { uv.set(u, v, 0f); return this; }
    @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
    @Override public VertexConsumer setColor(int color) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }
}
