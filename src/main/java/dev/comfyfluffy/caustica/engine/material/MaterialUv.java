package dev.comfyfluffy.caustica.engine.material;

/** Atlas origin and inverse extent used to map atlas UVs into a material's local UV space. */
public record MaterialUv(float u, float v, float inverseDu, float inverseDv) {
    public static final MaterialUv IDENTITY = new MaterialUv(0.0f, 0.0f, 1.0f, 1.0f);

    public MaterialUv {
        if (!Float.isFinite(u) || !Float.isFinite(v)
                || !Float.isFinite(inverseDu) || !Float.isFinite(inverseDv)) {
            throw new IllegalArgumentException("material UV transform must be finite");
        }
    }

    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof MaterialUv uv
                && Float.compare(inverseDv, uv.inverseDv) == 0
                && Float.compare(inverseDu, uv.inverseDu) == 0
                && Float.compare(v, uv.v) == 0
                && Float.compare(u, uv.u) == 0;
    }

    @Override
    public final int hashCode() {
        int result = Float.hashCode(u);
        result = 31 * result + Float.hashCode(v);
        result = 31 * result + Float.hashCode(inverseDu);
        return 31 * result + Float.hashCode(inverseDv);
    }
}
