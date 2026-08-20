package dev.comfyfluffy.caustica.minecraft.cloud;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Collections;
import java.util.Set;
import java.util.SplittableRandom;

/** Deterministic tessellation of a low block-shaped cloud as one closed rounded cuboid. */
public final class RoundedCloudMesh {
    private static final int CORNER_SEGMENTS = 3;
    private static final SceneMesh.TopologyRevision TOPOLOGY = new SceneMesh.TopologyRevision(1L);

    private RoundedCloudMesh() {
    }

    public static SceneMesh generate(long seed, MaterialHandle material) {
        Builder builder = new Builder();
        SplittableRandom random = new SplittableRandom(seed);
        float halfX = snapped(random.nextDouble(18.0, 27.0));
        float halfY = snapped(random.nextDouble(3.0, 5.0));
        float halfZ = snapped(random.nextDouble(8.0, 15.0));
        builder.roundedBox(0f, 0f, 0f, halfX, halfY, halfZ, Math.min(3f, halfY - 0.5f));
        int triangles = builder.indices.size() / 3;
        return new SceneMesh(builder.positions.toFloatArray(), builder.indices.toIntArray(),
                SceneMesh.UvLayout.PER_VERTEX, builder.texCoords.toFloatArray(),
                Collections.nCopies(triangles, SceneMesh.TriangleSurface.surface(material)), Set.of(), TOPOLOGY);
    }

    private static float snapped(double value) {
        return (float) (Math.rint(value / 2.0) * 2.0);
    }

    private static final class Builder {
        final FloatArrayList positions = new FloatArrayList();
        final FloatArrayList texCoords = new FloatArrayList();
        final IntArrayList indices = new IntArrayList();

        void roundedBox(float centerX, float centerY, float centerZ,
                        float halfX, float halfY, float halfZ, float radius) {
            face(centerX, centerY, centerZ, halfX, halfY, halfZ, radius, 0, 1f);
            face(centerX, centerY, centerZ, halfX, halfY, halfZ, radius, 0, -1f);
            face(centerX, centerY, centerZ, halfX, halfY, halfZ, radius, 1, 1f);
            face(centerX, centerY, centerZ, halfX, halfY, halfZ, radius, 1, -1f);
            face(centerX, centerY, centerZ, halfX, halfY, halfZ, radius, 2, 1f);
            face(centerX, centerY, centerZ, halfX, halfY, halfZ, radius, 2, -1f);
        }

        private void face(float cx, float cy, float cz, float hx, float hy, float hz, float radius,
                          int axis, float sign) {
            int base = positions.size() / 3;
            for (int v = 0; v <= CORNER_SEGMENTS; v++) {
                float fv = (2f * v / CORNER_SEGMENTS) - 1f;
                for (int u = 0; u <= CORNER_SEGMENTS; u++) {
                    float fu = (2f * u / CORNER_SEGMENTS) - 1f;
                    float x;
                    float y;
                    float z;
                    if (axis == 0) {
                        x = sign * hx;
                        y = fv * hy;
                        z = fu * hz;
                    } else if (axis == 1) {
                        x = fu * hx;
                        y = sign * hy;
                        z = fv * hz;
                    } else {
                        x = fu * hx;
                        y = fv * hy;
                        z = sign * hz;
                    }
                    float innerX = Math.max(0f, hx - radius);
                    float innerY = Math.max(0f, hy - radius);
                    float innerZ = Math.max(0f, hz - radius);
                    float coreX = clamp(x, -innerX, innerX);
                    float coreY = clamp(y, -innerY, innerY);
                    float coreZ = clamp(z, -innerZ, innerZ);
                    float dx = x - coreX;
                    float dy = y - coreY;
                    float dz = z - coreZ;
                    float scale = radius / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    positions.add(cx + coreX + dx * scale);
                    positions.add(cy + coreY + dy * scale);
                    positions.add(cz + coreZ + dz * scale);
                    texCoords.add((float) u / CORNER_SEGMENTS);
                    texCoords.add((float) v / CORNER_SEGMENTS);
                }
            }
            int stride = CORNER_SEGMENTS + 1;
            for (int v = 0; v < CORNER_SEGMENTS; v++) {
                for (int u = 0; u < CORNER_SEGMENTS; u++) {
                    int a = base + v * stride + u;
                    int b = a + 1;
                    int c = a + stride;
                    int d = c + 1;
                    triangleFacing(a, b, d, axis, sign);
                    triangleFacing(a, d, c, axis, sign);
                }
            }
        }

        private void triangleFacing(int a, int b, int c, int axis, float sign) {
            int ao = a * 3;
            int bo = b * 3;
            int co = c * 3;
            float abx = positions.getFloat(bo) - positions.getFloat(ao);
            float aby = positions.getFloat(bo + 1) - positions.getFloat(ao + 1);
            float abz = positions.getFloat(bo + 2) - positions.getFloat(ao + 2);
            float acx = positions.getFloat(co) - positions.getFloat(ao);
            float acy = positions.getFloat(co + 1) - positions.getFloat(ao + 1);
            float acz = positions.getFloat(co + 2) - positions.getFloat(ao + 2);
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float facing = axis == 0 ? nx : axis == 1 ? ny : nz;
            indices.add(a);
            if (facing * sign >= 0f) {
                indices.add(b);
                indices.add(c);
            } else {
                indices.add(c);
                indices.add(b);
            }
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
