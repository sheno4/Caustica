package dev.comfyfluffy.caustica.minecraft.content.material;


/** Canonicalizes Minecraft resource-pack channels while their borrowed/owned images share one lifetime. */
public final class MinecraftMaterialTextureSource implements MaterialTextureSource {
    private final MaterialImageSource albedo;
    private final MaterialImageSource specular;
    private final MaterialImageSource normal;
    private final boolean inferEmission;
    private final int[] alphaFrames;
    private final int alphaFrameRowSize;

    public MinecraftMaterialTextureSource(MaterialImageSource albedo, MaterialImageSource specular,
                                          MaterialImageSource normal, boolean inferEmission) {
        this(albedo, specular, normal, inferEmission, null, 1);
    }

    public MinecraftMaterialTextureSource(MaterialImageSource albedo, MaterialImageSource specular,
                                          MaterialImageSource normal, boolean inferEmission,
                                          int[] alphaFrames, int alphaFrameRowSize) {
        this.albedo = albedo;
        this.specular = specular;
        this.normal = normal;
        this.inferEmission = inferEmission;
        this.alphaFrames = alphaFrames == null ? null : alphaFrames.clone();
        this.alphaFrameRowSize = alphaFrameRowSize;
    }

    @Override
    public MaterialTextureImage open() throws java.io.IOException {
        MaterialImage a = null, s = null, n = null;
        try {
            a = albedo.open();
            s = specular == null ? null : specular.open();
            n = normal == null ? null : normal.open();
            return new Image(a, s, n, inferEmission, alphaFrames, alphaFrameRowSize);
        } catch (java.io.IOException | RuntimeException | Error failure) {
            closeImages(failure, n, s, a);
            throw failure;
        }
    }

    private static Throwable closeImages(Throwable failure, MaterialImage... images) {
        for (MaterialImage image : images) {
            if (image == null) continue;
            try {
                image.close();
            } catch (RuntimeException | Error close) {
                if (failure == null) failure = close;
                else if (failure != close) failure.addSuppressed(close);
            }
        }
        return failure;
    }

    private record Image(MaterialImage albedo, MaterialImage specular, MaterialImage normal,
                         boolean inferEmission, int[] alphaFrames,
                         int alphaFrameRowSize) implements MaterialTextureImage {
        @Override
        public int width() {
            return albedo.width();
        }

        @Override
        public int height() {
            return albedo.height();
        }

        @Override
        public int albedoArgb(int x, int y) {
            return albedo.argb(x, y);
        }

        @Override
        public int alphaArgb(int frameIndex, int x, int y) {
            if (alphaFrames == null) {
                if (frameIndex != 0) throw new IndexOutOfBoundsException(frameIndex);
                return albedoArgb(x, y);
            }
            int frame = alphaFrames[frameIndex];
            int frameX = frame % alphaFrameRowSize * width();
            int frameY = frame / alphaFrameRowSize * height();
            return albedo.argb(frameX + x, frameY + y);
        }

        @Override
        public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) {
            int ap = albedo.argb(x, y);
            float ar = linear(ap >>> 16 & 255);
            float ag = linear(ap >>> 8 & 255);
            float ab = linear(ap & 255);
            if (specular != null) {
                int p = sample(specular, x, y, width(), height());
                var decoded = MinecraftLabPbr.decodeSpec((p >>> 16 & 255) / 255.0f,
                        (p >>> 8 & 255) / 255.0f, (p & 255) / 255.0f, (p >>> 24) / 255.0f,
                        ar, ag, ab);
                out.specularRoughness = decoded.specularRoughness();
                out.baseMetalness = decoded.metalness();
                out.emissionWeight = decoded.emission();
                out.subsurfaceWeight = decoded.subsurfaceWeight();
                out.metalBaseColorR = decoded.metalBaseColorR();
                out.metalBaseColorG = decoded.metalBaseColorG();
                out.metalBaseColorB = decoded.metalBaseColorB();
                out.specularIor = decoded.specularIor();
            } else if (inferEmission) {
                out.emissionWeight = MinecraftEmissionHeuristic.weight(
                        ar, ag, ab, (ap >>> 24) / 255.0f);
            }
            if (normal != null) {
                int p = sample(normal, x, y, width(), height());
                float nx = (p >>> 16 & 255) / 127.5f - 1.0f;
                float ny = (p >>> 8 & 255) / 127.5f - 1.0f;
                float lengthSq = nx * nx + ny * ny;
                if (lengthSq > 1.0f) {
                    float inv = 1.0f / (float) Math.sqrt(lengthSq);
                    nx *= inv;
                    ny *= inv;
                }
                out.tangentNormalX = nx;
                out.tangentNormalY = ny;
                out.normalHeight = (p >>> 24) / 255.0f;
            }
        }

        @Override
        public void close() {
            Throwable failure = closeImages(null, normal, specular, albedo);
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }

        private static int sample(MaterialImage image, int x, int y, int width, int height) {
            return image.argb(Math.min(image.width() - 1, x * image.width() / width),
                    Math.min(image.height() - 1, y * image.height() / height));
        }

        private static float linear(int value) {
            float v = value / 255.0f;
            return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
        }
    }
}
