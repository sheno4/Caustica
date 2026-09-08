package dev.comfyfluffy.caustica.minecraft.content.material;

import java.util.ArrayList;
import java.util.List;

/** CPU OpenPBR planes used only while packing one resource-pack epoch. */
final class MaterialTextureLevels {
    record Level(int width, int height, float[] surface0, float[] normal,
                 float[] surface1, float[] emissionColor) {
        Level {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("texture level must be non-empty");
            int length = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            requireLength(surface0, length);
            requireLength(normal, length);
            requireLength(surface1, length);
            requireLength(emissionColor, length);
        }

        private static void requireLength(float[] values, int length) {
            if (values == null || values.length != length) {
                throw new IllegalArgumentException("texture plane length must match its RGBA extent");
            }
        }
    }

    private MaterialTextureLevels() {
    }

    static int unorm8(float value) {
        return Math.round(Math.clamp(value, 0.0f, 1.0f) * 255.0f);
    }

    static List<Level> mipChain(Level base, int maxLod) {
        List<Level> result = new ArrayList<>();
        result.add(base);
        Level previous = base;
        for (int lod = 1; lod <= maxLod && (previous.width() > 1 || previous.height() > 1); lod++) {
            previous = downsample(previous);
            result.add(previous);
        }
        return List.copyOf(result);
    }

    private static Level downsample(Level source) {
        int width = Math.max(1, source.width() / 2);
        int height = Math.max(1, source.height() / 2);
        return new Level(width, height,
                downsample(source.surface0(), source.width(), source.height(), width, height),
                downsample(source.normal(), source.width(), source.height(), width, height),
                downsample(source.surface1(), source.width(), source.height(), width, height),
                downsample(source.emissionColor(), source.width(), source.height(), width, height));
    }

    private static float[] downsample(float[] source, int sourceWidth, int sourceHeight,
                                      int width, int height) {
        float[] result = new float[width * height * 4];
        double scaleX = (double) sourceWidth / width;
        double scaleY = (double) sourceHeight / height;
        // Equal-area footprints include trailing odd texels and split fractional boundary texels.
        for (int y = 0; y < height; y++) {
            double top = (double) y * sourceHeight / height;
            double bottom = (double) (y + 1) * sourceHeight / height;
            for (int x = 0; x < width; x++) {
                double left = (double) x * sourceWidth / width;
                double right = (double) (x + 1) * sourceWidth / width;
                int destination = (y * width + x) * 4;
                for (int sy = (int) top; sy < Math.ceil(bottom); sy++) {
                    double overlapY = Math.min(bottom, sy + 1) - Math.max(top, sy);
                    for (int sx = (int) left; sx < Math.ceil(right); sx++) {
                        double overlapX = Math.min(right, sx + 1) - Math.max(left, sx);
                        float weight = (float) (overlapX * overlapY / (scaleX * scaleY));
                        int sourceOffset = (sy * sourceWidth + sx) * 4;
                        for (int channel = 0; channel < 4; channel++) {
                            result[destination + channel] += source[sourceOffset + channel] * weight;
                        }
                    }
                }
            }
        }
        return result;
    }
}
