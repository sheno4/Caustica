package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.SceneMesh;

/** Per-triangle texture and coverage facts packed into {@code Prim.aux0}. */
public final class PrimitiveMaterialAbi {
    public static final int COVERAGE_OPAQUE = 0;
    public static final int COVERAGE_CUTOUT = 1;
    public static final int COVERAGE_STOCHASTIC = 2;
    public static final int TEXTURE_PRESENT = 1 << 18;
    public static final int TEXTURE_LINEAR = 1 << 19;
    private static final int TEXTURE_MASK = 0xffff;
    private static final int COVERAGE_SHIFT = 16;
    private static final int COVERAGE_MASK = 3;

    private PrimitiveMaterialAbi() { }

    public static int pack(int textureSlot, SceneMesh.Coverage coverage, boolean texturePresent) {
        if (textureSlot < 0 || textureSlot > TEXTURE_MASK) {
            throw new IllegalArgumentException("provider texture slot exceeds primitive ABI");
        }
        int encodedCoverage = switch (coverage) {
            case OPAQUE -> COVERAGE_OPAQUE;
            case CUTOUT -> COVERAGE_CUTOUT;
            case STOCHASTIC -> COVERAGE_STOCHASTIC;
        };
        return textureSlot | (encodedCoverage << COVERAGE_SHIFT)
                | (texturePresent ? TEXTURE_PRESENT | TEXTURE_LINEAR : 0);
    }

    public static int textureSlot(int packed) { return packed & TEXTURE_MASK; }
    public static int coverage(int packed) {
        int coverage = (packed >>> COVERAGE_SHIFT) & COVERAGE_MASK;
        if (coverage > COVERAGE_STOCHASTIC) throw new IllegalArgumentException("invalid primitive coverage");
        return coverage;
    }
    public static boolean texturePresent(int packed) { return (packed & TEXTURE_PRESENT) != 0; }
    public static boolean textureLinear(int packed) { return (packed & TEXTURE_LINEAR) != 0; }
}
