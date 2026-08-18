package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.provider.MaterialTextureImage;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureSource;
import dev.comfyfluffy.caustica.api.provider.OpenPbrTextureTexel;

/** Canonical OpenPBR view of the images referenced by one glTF material. */
final class GltfMaterialTextureSource implements MaterialTextureSource {
    private final ImageData metallicRoughness;
    private final ImageData normal;
    private final ImageData emissive;
    private final int width;
    private final int height;
    private final float dielectricIor;
    private final float normalScale;

    GltfMaterialTextureSource(ImageData metallicRoughness,
                              ImageData normal, ImageData emissive,
                              int width, int height, float dielectricIor, float normalScale) {
        this.metallicRoughness = metallicRoughness;
        this.normal = normal;
        this.emissive = emissive;
        this.width = width;
        this.height = height;
        this.dielectricIor = dielectricIor;
        this.normalScale = normalScale;
    }

    @Override
    public MaterialTextureImage open() {
        return new Image();
    }

    @Override
    public int alphaFrameCount() {
        return 1;
    }

    record ImageData(int width, int height, int[] argb) {
        ImageData {
            argb = argb.clone();
            if (width <= 0 || height <= 0 || argb.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("decoded glTF image dimensions do not match its pixels");
            }
        }

        @Override
        public int[] argb() {
            return argb.clone();
        }

        int sample(int x, int y, int targetWidth, int targetHeight) {
            int sourceX = Math.min(width - 1, x * width / targetWidth);
            int sourceY = Math.min(height - 1, y * height / targetHeight);
            return argb[sourceY * width + sourceX];
        }
    }

    private final class Image implements MaterialTextureImage {
        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int albedoArgb(int x, int y) {
            return 0xffffffff;
        }

        @Override
        public int alphaFrameCount() {
            return 1;
        }

        @Override
        public int alphaArgb(int frame, int x, int y) {
            if (frame != 0) {
                throw new IndexOutOfBoundsException(frame);
            }
            return albedoArgb(x, y);
        }

        @Override
        public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) {
            if (metallicRoughness != null) {
                int pixel = metallicRoughness.sample(x, y, width, height);
                out.specularRoughness = (pixel >>> 8 & 255) / 255.0f;
                out.baseMetalness = (pixel & 255) / 255.0f;
                out.specularIor = dielectricIor;
            }
            if (normal != null) {
                int pixel = normal.sample(x, y, width, height);
                float nx = ((pixel >>> 16 & 255) / 127.5f - 1.0f) * normalScale;
                float ny = ((pixel >>> 8 & 255) / 127.5f - 1.0f) * normalScale;
                float lengthSquared = nx * nx + ny * ny;
                if (lengthSquared > 1.0f) {
                    float inverseLength = 1.0f / (float) Math.sqrt(lengthSquared);
                    nx *= inverseLength;
                    ny *= inverseLength;
                }
                out.tangentNormalX = nx;
                out.tangentNormalY = ny;
            }
            if (emissive != null) {
                int pixel = emissive.sample(x, y, width, height);
                out.emissionWeight = 1.0f;
                out.emissionColorR = srgbToLinear(pixel >>> 16 & 255);
                out.emissionColorG = srgbToLinear(pixel >>> 8 & 255);
                out.emissionColorB = srgbToLinear(pixel & 255);
            }
        }

        @Override
        public void close() {
        }
    }

    private static float srgbToLinear(int channel) {
        float value = channel / 255.0f;
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }
}
