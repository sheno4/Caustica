package dev.comfyfluffy.caustica.minecraft.client.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Expands each raster line segment into two crossed, camera-independent RT ribbons. */
final class RtLineVertexConsumer implements VertexConsumer {
    private static final float[] ZERO_UV = new float[4];
    private static final float WORLD_UNITS_PER_PIXEL = 0.0025f;
    private final float[] ax = new float[4], ay = new float[4], az = new float[4];
    private RtEntityCapture capture;
    private float x, y, z, width;
    private int color;
    private boolean pending;
    private boolean haveFirst;
    private float firstX, firstY, firstZ, firstWidth;
    private int firstColor;

    void begin(RtEntityCapture capture) {
        this.capture = capture;
        pending = false;
        haveFirst = false;
    }

    void finish() {
        commit();
        if (haveFirst) {
            haveFirst = false;
            throw new IllegalStateException("custom line geometry left an unmatched vertex");
        }
    }

    private void commit() {
        if (!pending) {
            return;
        }
        if (!haveFirst) {
            firstX = x;
            firstY = y;
            firstZ = z;
            firstWidth = width;
            firstColor = color;
            haveFirst = true;
        } else {
            emitSegment(firstX, firstY, firstZ, x, y, z,
                    Math.max(firstWidth, width), firstColor);
            haveFirst = false;
        }
        pending = false;
    }

    private void emitSegment(float x0, float y0, float z0, float x1, float y1, float z1,
                             float pixelWidth, int color) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0e-6f) {
            return;
        }
        dx /= length;
        dy /= length;
        dz /= length;
        float halfWidth = Math.max(0.0015f, pixelWidth * WORLD_UNITS_PER_PIXEL * 0.5f);

        // Cross the segment with the least-parallel cardinal axis for a stable perpendicular.
        float px, py, pz;
        float adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
        if (adx <= ady && adx <= adz) {
            px = 0f;
            py = dz;
            pz = -dy;
        } else if (ady <= adz) {
            px = -dz;
            py = 0f;
            pz = dx;
        } else {
            px = dy;
            py = -dx;
            pz = 0f;
        }
        float plen = (float) Math.sqrt(px * px + py * py + pz * pz);
        px = px / plen * halfWidth;
        py = py / plen * halfWidth;
        pz = pz / plen * halfWidth;
        emitRibbon(x0, y0, z0, x1, y1, z1, px, py, pz, color);

        float qx = (dy * pz - dz * py);
        float qy = (dz * px - dx * pz);
        float qz = (dx * py - dy * px);
        emitRibbon(x0, y0, z0, x1, y1, z1, qx, qy, qz, color);
    }

    private void emitRibbon(float x0, float y0, float z0, float x1, float y1, float z1,
                            float px, float py, float pz, int color) {
        ax[0] = x0 - px; ay[0] = y0 - py; az[0] = z0 - pz;
        ax[1] = x1 - px; ay[1] = y1 - py; az[1] = z1 - pz;
        ax[2] = x1 + px; ay[2] = y1 + py; az[2] = z1 + pz;
        ax[3] = x0 + px; ay[3] = y0 + py; az[3] = z0 + pz;
        capture.addDirectQuad(ax, ay, az, ZERO_UV, ZERO_UV, 0f, 0f, 0f, color);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        commit();
        this.x = x;
        this.y = y;
        this.z = z;
        this.width = 1f;
        this.color = -1;
        this.pending = true;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        color = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        return this;
    }

    @Override public VertexConsumer setColor(int color) { this.color = color; return this; }
    @Override public VertexConsumer setUv(float u, float v) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { this.width = width; return this; }
}
