package dev.comfyfluffy.caustica.rt.material;

/** Packing contract shared by host material bindings and {@code world_common.slang}. */
public final class MaterialBindingAbi {
    public static final int COVERAGE_OPAQUE = 0;
    public static final int COVERAGE_CUTOUT = 1;
    public static final int COVERAGE_STOCHASTIC = 2;
    public static final int FLAG_TRANSMISSIVE = 1;
    public static final int FLAG_TEXTURELESS = 4;

    private static final int BASE_COLOR_TEXTURE_INDEX_MASK = 0xFFFF;
    private static final int COVERAGE_SHIFT = 16;
    private static final int COVERAGE_MASK = 3;
    private static final int FLAGS_SHIFT = 18;
    private static final int FLAGS_MASK = 63;
    private static final int SURFACE_IMPL_SHIFT = 24;
    private static final int SURFACE_IMPL_MASK = 255;
    private static final int CUTOFF_MASK = 255;

    private MaterialBindingAbi() {
    }

    public static int pack(int baseColorTextureIndex, int coverageMode, int flags,
                           int surfaceImplementation) {
        return (baseColorTextureIndex & BASE_COLOR_TEXTURE_INDEX_MASK)
                | ((coverageMode & COVERAGE_MASK) << COVERAGE_SHIFT)
                | ((flags & FLAGS_MASK) << FLAGS_SHIFT)
                | ((surfaceImplementation & SURFACE_IMPL_MASK) << SURFACE_IMPL_SHIFT);
    }

    public static int surfaceImplementation(int packed) {
        return (packed >>> SURFACE_IMPL_SHIFT) & SURFACE_IMPL_MASK;
    }

    public static int baseColorTextureIndex(int packed) {
        return packed & BASE_COLOR_TEXTURE_INDEX_MASK;
    }

    public static int coverage(int packed) {
        return (packed >>> COVERAGE_SHIFT) & COVERAGE_MASK;
    }

    public static int flags(int packed) {
        return (packed >>> FLAGS_SHIFT) & FLAGS_MASK;
    }

    public static int packCoverageCutoff(float cutoff) {
        return Math.round(cutoff * CUTOFF_MASK) & CUTOFF_MASK;
    }

    public static float coverageCutoff(int packed) {
        return (packed & CUTOFF_MASK) / (float) CUTOFF_MASK;
    }
}
