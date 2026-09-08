package dev.comfyfluffy.caustica.minecraft.content.material;

/**
 * Adapter from the LabPBR 1.3 specular texture into the engine's OpenPBR vocabulary. LabPBR is a source
 * format, not a material model: it authors normal-incidence reflectance and perceptual smoothness, where
 * the supported OpenPBR subset authors {@code specular_ior}, conductor {@code base_color}, and
 * {@code specular_roughness}.
 */
public final class MinecraftLabPbr {
    private MinecraftLabPbr() {
    }

    /**
     * Ceiling on the stored amplitude reflectance. IOR diverges as it approaches one, so the encoding
     * stops one unorm8 step short and caps at the ~509 that step corresponds to.
     */
    private static final float MAX_AMPLITUDE = 254.0f / 255.0f;

    private static final float[][] METAL_N = {
            {2.9114f, 2.9497f, 2.5845f},
            {0.18299f, 0.42108f, 1.3734f},
            {1.3456f, 0.96521f, 0.61722f},
            {3.1071f, 3.1812f, 2.3230f},
            {0.27105f, 0.67693f, 1.3164f},
            {1.9100f, 1.8300f, 1.4400f},
            {2.3757f, 2.0847f, 1.8453f},
            {0.15943f, 0.14512f, 0.13547f}
    };
    private static final float[][] METAL_K = {
            {3.0893f, 2.9318f, 2.7670f},
            {3.4242f, 2.3459f, 1.7704f},
            {7.4746f, 6.3995f, 5.3031f},
            {3.3314f, 3.3291f, 3.1350f},
            {3.6092f, 2.6248f, 2.2921f},
            {3.5100f, 3.4000f, 3.1800f},
            {4.2655f, 3.7153f, 3.1365f},
            {3.9291f, 3.1900f, 2.3808f}
    };

    /** Decode normalized LabPBR channels and linear albedo into OpenPBR surface parameters. */
    public static Texel decodeSpec(float red, float green, float blue, float alpha,
                                   float albedoR, float albedoG, float albedoB) {
        // LabPBR red is perceptual smoothness and OpenPBR specular_roughness is perceptual roughness, so
        // the two differ only by direction. The squaring that turns this into a GGX alpha happens in the
        // shader, where the lobe is evaluated.
        float specularRoughness = 1.0f - clamp01(red);
        float g = clamp01(green) * 255.0f;
        float metalness;
        float specularIor;
        float metalBaseColorR;
        float metalBaseColorG;
        float metalBaseColorB;
        if (g < 229.5f) {
            // Dielectric: the authored reflectance is a scalar, so it carries no tint and inverts
            // entirely into specular_ior. The RGB page lanes are neutral because no metal colour applies.
            metalness = 0.0f;
            specularIor = iorFromF0(clamp01(green));
            metalBaseColorR = metalBaseColorG = metalBaseColorB = 1.0f;
        } else {
            // Metal: a conductor's normal-incidence reflectance IS its OpenPBR base_color, either from
            // the predefined n/k table or, above the table's range, from the albedo itself. A metal has
            // no dielectric index to author, so it carries the default one: a mip that straddles a
            // metal/dielectric border then blends toward the ordinary dielectric reflectance rather than
            // toward none at all.
            metalness = 1.0f;
            specularIor = OpenPbrDefaults.SPECULAR_IOR;
            if (g < 237.5f) {
                int metal = Math.round(g) - 230;
                metalBaseColorR = metalF0(metal, 0);
                metalBaseColorG = metalF0(metal, 1);
                metalBaseColorB = metalF0(metal, 2);
            } else {
                metalBaseColorR = clamp01(albedoR);
                metalBaseColorG = clamp01(albedoG);
                metalBaseColorB = clamp01(albedoB);
            }
        }

        float a = clamp01(alpha) * 255.0f;
        float emission = a < 254.5f ? a / 254.0f : 0.0f;
        float b = clamp01(blue) * 255.0f;
        float subsurface = b > 64.5f ? (b - 65.0f) / 190.0f : 0.0f;
        return new Texel(specularRoughness, metalness, specularIor,
                metalBaseColorR, metalBaseColorG, metalBaseColorB, emission, subsurface);
    }

    /**
     * OpenPBR {@code specular_ior} for an authored normal-incidence reflectance. The Fresnel relation
     * {@code F0 = ((ior - 1) / (ior + 1))^2} inverted for the side of it a source format authors.
     */
    public static float iorFromF0(float f0) {
        float amplitude = Math.min(MAX_AMPLITUDE, (float) Math.sqrt(clamp01(f0)));
        return (1.0f + amplitude) / (1.0f - amplitude);
    }

    private static float metalF0(int metal, int channel) {
        float n = METAL_N[metal][channel];
        float k = METAL_K[metal][channel];
        float nm1 = n - 1.0f;
        float np1 = n + 1.0f;
        return (nm1 * nm1 + k * k) / (np1 * np1 + k * k);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    /** One decoded texel in the supported OpenPBR vocabulary. */
    public record Texel(float specularRoughness, float metalness, float specularIor,
                        float metalBaseColorR, float metalBaseColorG, float metalBaseColorB,
                        float emission, float subsurfaceWeight) {
    }
}
