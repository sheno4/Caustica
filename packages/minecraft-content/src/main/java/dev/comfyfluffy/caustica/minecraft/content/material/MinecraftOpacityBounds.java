package dev.comfyfluffy.caustica.minecraft.content.material;

/** Immutable mip-zero alpha extrema across every animation frame in one resource epoch. */
public final class MinecraftOpacityBounds {
    private final int width;
    private final int height;
    private final MaterialUv uv;
    private final byte[] minimum;
    private final byte[] maximum;

    private MinecraftOpacityBounds(int width, int height, MaterialUv uv, byte[] minimum, byte[] maximum) {
        this.width = width;
        this.height = height;
        this.uv = uv;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public static MinecraftOpacityBounds scan(MaterialTextureResource resource) throws java.io.IOException {
        var source = resource.analysisSource();
        if (source.alphaFrameCount() == 0) return null;
        byte[] minimum = new byte[Math.multiplyExact(source.width(), source.height())];
        byte[] maximum = new byte[minimum.length];
        java.util.Arrays.fill(minimum, (byte) 255);
        try (var image = source.texture().open()) {
            for (int frame = 0; frame < source.alphaFrameCount(); frame++) {
                for (int y = 0; y < source.height(); y++) {
                    for (int x = 0; x < source.width(); x++) {
                        int pixel = y * source.width() + x;
                        int alpha = image.alphaArgb(frame, x, y) >>> 24;
                        minimum[pixel] = (byte) Math.min(minimum[pixel] & 255, alpha);
                        maximum[pixel] = (byte) Math.max(maximum[pixel] & 255, alpha);
                    }
                }
            }
        }
        return new MinecraftOpacityBounds(source.width(), source.height(), resource.albedoUv(), minimum, maximum);
    }

    public int width() { return width; }
    public int height() { return height; }
    public MaterialUv uv() { return uv; }
    public int alpha(int bound, int x, int y) {
        return (bound == 0 ? minimum : maximum)[y * width + x] & 255;
    }
}
