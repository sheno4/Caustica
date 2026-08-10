package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;

/**
 * Adapter from the LabPBR 1.3 specular texture into the engine's OpenPBR vocabulary. LabPBR is a source
 * format, not a material model: it authors normal-incidence reflectance and perceptual smoothness, where
 * OpenPBR authors {@code specular_ior}/{@code specular_color} and {@code specular_roughness}. Inverting
 * that is this class's job, and it is the only place the source format's conventions are known.
 */
public final class RtLabPbr {
    private RtLabPbr() {
    }

    /**
     * Ceiling on the stored amplitude reflectance. IOR diverges as it approaches one, so the encoding
     * stops one unorm8 step short and caps at the ~509 that step corresponds to.
     */
    private static final float MAX_AMPLITUDE = 254.0f / 255.0f;

    /**
     * What the source format's strongest subsurface authoring becomes as an OpenPBR transmission weight.
     *
     * <p>There is no subsurface parameter in this engine: OpenPBR's subsurface lobe degenerates into
     * diffuse reflection and transmission on a surface with no interior, and that degeneration is the
     * only subsurface transport here, so the authored channel is a transmission weight and nothing else.
     * One half is the symmetric point — the diffuse response splits evenly between the two sides, so the
     * surface answers a light from either side identically. That is the most a leaf can scatter through
     * without transmitting more than it reflects, and it is exactly what a billboard is, which is why
     * fully-authored foliage and a particle land on the same value.
     */
    private static final float SYMMETRIC_THIN_TRANSMISSION = 0.5f;

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
        float colorR;
        float colorG;
        float colorB;
        if (g < 229.5f) {
            // Dielectric: the authored reflectance is a scalar, so it carries no tint and inverts
            // entirely into specular_ior. specular_color stays white — LabPBR cannot express one.
            metalness = 0.0f;
            specularIor = iorFromF0(clamp01(green));
            colorR = colorG = colorB = 1.0f;
        } else {
            // Metal: a conductor's normal-incidence reflectance IS its OpenPBR base_color, either from
            // the predefined n/k table or, above the table's range, from the albedo itself. A metal has
            // no dielectric index to author, so it carries the default one: a mip that straddles a
            // metal/dielectric border then blends toward the ordinary dielectric reflectance rather than
            // toward none at all.
            metalness = 1.0f;
            specularIor = OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR;
            if (g < 237.5f) {
                int metal = Math.round(g) - 230;
                colorR = metalF0(metal, 0);
                colorG = metalF0(metal, 1);
                colorB = metalF0(metal, 2);
            } else {
                colorR = clamp01(albedoR);
                colorG = clamp01(albedoG);
                colorB = clamp01(albedoB);
            }
        }

        float a = clamp01(alpha) * 255.0f;
        float emission = a < 254.5f ? a / 254.0f : 0.0f;
        float b = clamp01(blue) * 255.0f;
        float subsurface = b > 64.5f ? (b - 65.0f) / 190.0f : 0.0f;
        return new Texel(specularRoughness, metalness, specularIor, colorR, colorG, colorB, emission,
                subsurface * SYMMETRIC_THIN_TRANSMISSION);
    }

    /**
     * OpenPBR {@code specular_ior} for an authored normal-incidence reflectance. The Fresnel relation
     * {@code F0 = ((ior - 1) / (ior + 1))^2} inverted for the side of it a source format authors.
     */
    public static float iorFromF0(float f0) {
        return decodeIor((float) Math.sqrt(clamp01(f0)));
    }

    /**
     * Page storage encoding of {@code specular_ior}: its amplitude reflectance {@code (ior-1)/(ior+1)}.
     * Monotonic and bounded, so the whole dielectric range fits a unorm8 with a step of about 0.008 in
     * IOR where real materials sit. {@code surfaceIorFromPage} in world_common.slang is the decode.
     */
    public static float encodeIor(float ior) {
        float amplitude = (ior - 1.0f) / (ior + 1.0f);
        return Math.max(0.0f, Math.min(MAX_AMPLITUDE, amplitude));
    }

    public static float decodeIor(float encoded) {
        float amplitude = Math.max(0.0f, Math.min(MAX_AMPLITUDE, encoded));
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

    /**
     * One decoded texel in OpenPBR vocabulary. {@code colorR/G/B} is the one union the source format
     * forces: {@code base_color} where {@code metalness} is 1, {@code specular_color} where it is 0. A
     * LabPBR texel authors exactly one reflectance and its own metalness says which parameter that is,
     * so a single set of channels can carry both without ambiguity.
     */
    public record Texel(float specularRoughness, float metalness, float specularIor,
                        float colorR, float colorG, float colorB,
                        float emission, float transmissionWeight) {
    }
}
