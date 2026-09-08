package dev.comfyfluffy.caustica.minecraft.content.material;

/** Atlas origin and inverse extent used to map atlas UVs into a material's local UV space. */
public record MaterialUv(float u, float v, float inverseDu, float inverseDv) {
    public static final MaterialUv IDENTITY = new MaterialUv(0.0f, 0.0f, 1.0f, 1.0f);

    public MaterialUv {
        if (!Float.isFinite(u) || !Float.isFinite(v)
                || !Float.isFinite(inverseDu) || !Float.isFinite(inverseDv)) {
            throw new IllegalArgumentException("material UV transform must be finite");
        }
    }

}
