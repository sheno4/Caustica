package dev.comfyfluffy.caustica.rt.material;

/** Packing contract shared by host material bindings and {@code world_common.slang}. */
public final class MaterialBindingAbi {
    public static final int FLAG_TRANSMISSIVE = 1;
    private static final int FLAGS_MASK = 255;
    private static final int SURFACE_IMPL_SHIFT = 24;
    private static final int SURFACE_IMPL_MASK = 255;
    private static final int CUTOFF_MASK = 255;

    private MaterialBindingAbi() {
    }

    public static int pack(int flags, int surfaceImplementation) {
        return (flags & FLAGS_MASK)
                | ((surfaceImplementation & SURFACE_IMPL_MASK) << SURFACE_IMPL_SHIFT);
    }

    public static int surfaceImplementation(int packed) {
        return (packed >>> SURFACE_IMPL_SHIFT) & SURFACE_IMPL_MASK;
    }

    public static int flags(int packed) {
        return packed & FLAGS_MASK;
    }

    public static int packCoverageCutoff(float cutoff) {
        return Math.round(cutoff * CUTOFF_MASK) & CUTOFF_MASK;
    }

    public static float coverageCutoff(int packed) {
        return (packed & CUTOFF_MASK) / (float) CUTOFF_MASK;
    }
}
