package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

/** Source integer viewport sizing. Parameters come from the private source manifest. */
public final class CloudlyAtmosphereDimensions {
    private CloudlyAtmosphereDimensions() { }

    public record Extent(int width, int height, int depth) {
        public Extent {
            if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("Invalid atmosphere extent");
        }
        public double[] values() { return new double[]{width, height, depth}; }
        public int[] groupsXY(int threadsX, int threadsY) {
            if (threadsX <= 0 || threadsY <= 0) throw new IllegalArgumentException("Invalid atmosphere group size");
            return new int[]{(width - 1) / threadsX + 1, (height - 1) / threadsY + 1, 1};
        }
    }

    /** Signed integer multiply and truncating division preserve the source size producer. */
    public static Extent aerialPerspective(int viewportWidth, int viewportHeight, int maxLongEdge, int depth) {
        if (viewportWidth < 0 || viewportHeight < 0 || maxLongEdge <= 0 || depth <= 0)
            throw new IllegalArgumentException("Invalid source atmosphere dimensions");
        if (viewportWidth == 0 || viewportHeight == 0) return new Extent(8, 8, depth);
        if (viewportWidth > viewportHeight) {
            int width = Math.min(viewportWidth, maxLongEdge);
            return new Extent(width, Math.max(1, width * viewportHeight / viewportWidth), depth);
        }
        int height = Math.min(viewportHeight, maxLongEdge);
        return new Extent(Math.max(1, height * viewportWidth / viewportHeight), height, depth);
    }
}
