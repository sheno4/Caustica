package dev.comfyfluffy.caustica.engine.light;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable worker-built LBVH over host-neutral finite lights. */
public final class LightBvh {
    private LightBvh() {
    }

    public static Data build(List<FiniteLight> lights, BooleanSupplier cancelled) {
        if (lights.isEmpty()) return Data.EMPTY;

        Aabb centroidBounds = Aabb.point(lights.get(0).descriptor().positionX(),
                lights.get(0).descriptor().positionY(), lights.get(0).descriptor().positionZ());
        for (int i = 1; i < lights.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            LightDescriptor.Finite light = lights.get(i).descriptor();
            centroidBounds = centroidBounds.include(light.positionX(), light.positionY(),
                    light.positionZ());
        }
        // Decorate-sort: the Morton key occupies the high 30 bits and the light index the low 32, so one
        // primitive sort yields Morton order with a stable index tiebreak and computes each key once.
        long[] order = new long[lights.size()];
        for (int i = 0; i < order.length; i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            order[i] = (mortonKey(lights.get(i).descriptor(), centroidBounds) << 32) | i;
        }
        Arrays.sort(order);

        ArrayList<Node> nodes = new ArrayList<>(Math.subtractExact(
                Math.multiplyExact(lights.size(), 2), 1));
        int root = buildRange(lights, order, 0, order.length, nodes, cancelled);
        int maxDepth = depth(nodes, root);
        return new Data(List.copyOf(lights), List.copyOf(nodes), root, maxDepth);
    }

    private static int buildRange(List<FiniteLight> lights, long[] order, int first, int end,
                                  ArrayList<Node> nodes, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        if (end - first == 1) {
            int lightIndex = (int) order[first];
            FiniteLight light = lights.get(lightIndex);
            int nodeIndex = nodes.size();
            nodes.add(Node.leaf(lightIndex, light));
            return nodeIndex;
        }
        int middle = (first + end) >>> 1;
        int left = buildRange(lights, order, first, middle, nodes, cancelled);
        int right = buildRange(lights, order, middle, end, nodes, cancelled);
        Node a = nodes.get(left);
        Node b = nodes.get(right);
        int nodeIndex = nodes.size();
        nodes.add(Node.branch(left, right, a, b));
        return nodeIndex;
    }

    private static int depth(List<Node> nodes, int nodeIndex) {
        Node node = nodes.get(nodeIndex);
        return node.isLeaf() ? 1 : 1 + Math.max(depth(nodes, node.left()), depth(nodes, node.right()));
    }

    /** 10 bits per axis, so the interleaved key fits above a 32-bit index in one long. */
    private static long mortonKey(LightDescriptor.Finite light, Aabb bounds) {
        long x = spread3(quantize(light.positionX(), bounds.minX(), bounds.maxX()));
        long y = spread3(quantize(light.positionY(), bounds.minY(), bounds.maxY()));
        long z = spread3(quantize(light.positionZ(), bounds.minZ(), bounds.maxZ()));
        return x | (y << 1) | (z << 2);
    }

    private static int quantize(double value, double min, double max) {
        if (!(max > min)) return 0;
        double unit = Math.clamp((value - min) / (max - min), 0.0, 1.0);
        return (int) Math.round(unit * 0x3ff);
    }

    private static long spread3(int value) {
        long x = value & 0x3ffL;
        x = (x | x << 16) & 0xff0000ffL;
        x = (x | x << 8) & 0x0300f00fL;
        x = (x | x << 4) & 0x030c30c3L;
        return (x | x << 2) & 0x09249249L;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Superseded light BVH build");
        }
    }

    public record Aabb(double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ) {
        public Aabb {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("AABB minima must not exceed maxima");
            }
        }

        public static Aabb point(double x, double y, double z) {
            return new Aabb(x, y, z, x, y, z);
        }

        public static Aabb around(double x, double y, double z,
                                  double extentX, double extentY, double extentZ) {
            return new Aabb(x - extentX, y - extentY, z - extentZ,
                    x + extentX, y + extentY, z + extentZ);
        }

        public Aabb include(double x, double y, double z) {
            return new Aabb(Math.min(minX, x), Math.min(minY, y), Math.min(minZ, z),
                    Math.max(maxX, x), Math.max(maxY, y), Math.max(maxZ, z));
        }

        public Aabb union(Aabb other) {
            return new Aabb(Math.min(minX, other.minX), Math.min(minY, other.minY),
                    Math.min(minZ, other.minZ), Math.max(maxX, other.maxX),
                    Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
        }

        public boolean contains(Aabb other) {
            return minX <= other.minX && minY <= other.minY && minZ <= other.minZ
                    && maxX >= other.maxX && maxY >= other.maxY && maxZ >= other.maxZ;
        }
    }

    /** Cone containing all outward emission directions represented by a node. */
    public record OrientationCone(double axisX, double axisY, double axisZ,
                                  double halfAngleRadians) {
        public OrientationCone {
            double length = Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
            if (!(length > 0.0) || !Double.isFinite(length)
                    || halfAngleRadians < 0.0 || halfAngleRadians > Math.PI
                    || !Double.isFinite(halfAngleRadians)) {
                throw new IllegalArgumentException("Invalid orientation cone");
            }
            axisX /= length;
            axisY /= length;
            axisZ /= length;
        }

        public static OrientationCone omnidirectional() {
            return new OrientationCone(0.0, 1.0, 0.0, Math.PI);
        }

        public boolean contains(OrientationCone other, double tolerance) {
            if (halfAngleRadians >= Math.PI) return true;
            double separation = angle(axisX, axisY, axisZ,
                    other.axisX, other.axisY, other.axisZ);
            return separation + other.halfAngleRadians <= halfAngleRadians + tolerance;
        }

        static OrientationCone union(OrientationCone a, OrientationCone b) {
            if (a.halfAngleRadians >= Math.PI || b.halfAngleRadians >= Math.PI) {
                return omnidirectional();
            }
            double separation = angle(a.axisX, a.axisY, a.axisZ,
                    b.axisX, b.axisY, b.axisZ);
            if (a.halfAngleRadians >= separation + b.halfAngleRadians) return a;
            if (b.halfAngleRadians >= separation + a.halfAngleRadians) return b;
            double halfAngle = 0.5 * (separation + a.halfAngleRadians + b.halfAngleRadians);
            if (halfAngle >= Math.PI) return omnidirectional();
            double rotation = halfAngle - a.halfAngleRadians;
            double[] axis = rotateToward(a, b, separation, rotation);
            return new OrientationCone(axis[0], axis[1], axis[2], halfAngle);
        }

        private static double[] rotateToward(OrientationCone a, OrientationCone b,
                                             double separation, double rotation) {
            if (separation < 1.0e-12) return new double[]{a.axisX, a.axisY, a.axisZ};
            double dot = Math.clamp(a.axisX * b.axisX + a.axisY * b.axisY
                    + a.axisZ * b.axisZ, -1.0, 1.0);
            double tx = b.axisX - dot * a.axisX;
            double ty = b.axisY - dot * a.axisY;
            double tz = b.axisZ - dot * a.axisZ;
            double tangentLength = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tangentLength > 0.0) {
                tx /= tangentLength;
                ty /= tangentLength;
                tz /= tangentLength;
                return new double[]{Math.cos(rotation) * a.axisX + Math.sin(rotation) * tx,
                        Math.cos(rotation) * a.axisY + Math.sin(rotation) * ty,
                        Math.cos(rotation) * a.axisZ + Math.sin(rotation) * tz};
            }
            // Antipodal axes have no unique shortest arc; choose a stable perpendicular.
            double px = Math.abs(a.axisX) < 0.9 ? 0.0 : -a.axisZ;
            double py = Math.abs(a.axisX) < 0.9 ? -a.axisZ : 0.0;
            double pz = Math.abs(a.axisX) < 0.9 ? a.axisY : a.axisX;
            double length = Math.sqrt(px * px + py * py + pz * pz);
            px /= length;
            py /= length;
            pz /= length;
            return new double[]{Math.cos(rotation) * a.axisX + Math.sin(rotation) * px,
                    Math.cos(rotation) * a.axisY + Math.sin(rotation) * py,
                    Math.cos(rotation) * a.axisZ + Math.sin(rotation) * pz};
        }

        private static double angle(double ax, double ay, double az,
                                    double bx, double by, double bz) {
            return Math.acos(Math.clamp(ax * bx + ay * by + az * bz, -1.0, 1.0));
        }
    }

    /**
     * Summed peak intensity is an upper bound on what the subtree can deliver along any one direction —
     * its lights are neither co-located nor aligned — which is what an importance estimate wants.
     */
    public record Node(int left, int right, int lightIndex, Aabb bounds,
                       double luminousPowerLumens, double peakLuminousIntensityCandela,
                       OrientationCone orientation) {
        static Node leaf(int lightIndex, FiniteLight light) {
            return new Node(-1, -1, lightIndex, light.bounds(), light.luminousPowerLumens(),
                    light.peakLuminousIntensityCandela(), light.orientation());
        }

        static Node branch(int left, int right, Node a, Node b) {
            return new Node(left, right, -1, a.bounds.union(b.bounds),
                    a.luminousPowerLumens + b.luminousPowerLumens,
                    a.peakLuminousIntensityCandela + b.peakLuminousIntensityCandela,
                    OrientationCone.union(a.orientation, b.orientation));
        }

        public boolean isLeaf() {
            return lightIndex >= 0;
        }
    }

    public record Data(List<FiniteLight> lights, List<Node> nodes, int rootIndex, int maxDepth) {
        private static final Data EMPTY = new Data(List.of(), List.of(), -1, 0);

        public double totalLuminousPowerLumens() {
            return rootIndex >= 0 ? nodes.get(rootIndex).luminousPowerLumens() : 0.0;
        }
    }
}
