module caustica_types;

// Extension colours and radiance are scene-linear ACEScg. Distances are Minecraft blocks and
// directions are normalized and point away from the shaded position.
public static const uint EVENT_NONE = 0x0u;
public static const uint EVENT_DIFFUSE = 0x1u;
public static const uint EVENT_GLOSSY = 0x2u;
public static const uint EVENT_SPECULAR = 0x4u;
public static const uint EVENT_REFLECTION = 0x8u;
public static const uint EVENT_TRANSMISSION = 0x10u;

public static const uint DIMENSION_ULTRAWARM = 0x4u;

/**
 * The authored surface description, a subset of OpenPBR Surface. Field names are the spec's, so a value
 * here means what the spec says it means — most of all `specularRoughness`, which is OpenPBR r and NOT
 * the GGX alpha the BSDF consumes (alpha = r * r).
 *
 * Normal-incidence reflectance is deliberately absent: OpenPBR derives it from `specularIor` and
 * `specularColor` for a dielectric and from `baseColor` for a metal. Use `openPbrSpecularF0`.
 */
public struct MaterialInput {
    public float3 baseColor;
    public float baseMetalness;
    /** OpenPBR specular_roughness: perceptual. GGX alpha is its square. */
    public float specularRoughness;
    public float specularIor;
    /** OpenPBR specular_color: a tint on the Fresnel factor, white when the material does not tint it. */
    public float3 specularColor;
    /**
     * OpenPBR transmission_weight. What it means depends on `geometryThinWalled`, exactly as the spec
     * says it should: through a thin wall it is DIFFUSE transmission — the fraction of the diffuse
     * response that leaves the far side — and through a volume it is the refractive transmission the
     * engine's dielectric transport owns. There is no separate subsurface parameter: OpenPBR's
     * subsurface lobe degenerates into diffuse reflection and transmission in thin-walled mode, and that
     * degeneration is the only subsurface transport this engine implements, so it is spelled here.
     */
    public float transmissionWeight;
    /**
     * OpenPBR emission_color and emission_luminance (cd/m²). Minecraft's convention is that an emitter
     * glows in its own texture colour, so the engine's material adapter sets emissionColor = baseColor;
     * that is the adapter's choice, not the model's, and a slot is free to compute its own.
     */
    public float3 emissionColor;
    public float emissionLuminance;
    /** OpenPBR geometry_opacity: coverage, i.e. whether the surface is present over this footprint. */
    public float geometryOpacity;
    /**
     * OpenPBR geometry_thin_walled: one surface with no interior — a leaf, a blade of grass, a billboard,
     * and equally the shell of an opaque block, since nothing is ever transported through its inside.
     * Glass is the opposite case: two interfaces with a real interior, so the engine refracts and pushes
     * a medium instead, and those surfaces never reach this description. It selects which transmission
     * `transmissionWeight` means.
     */
    public bool geometryThinWalled;
    public uint materialTags;
};

/**
 * OpenPBR normal-incidence reflectance. Not an authored parameter: a dielectric's follows from
 * `specularIor` and is tinted by `specularColor`; a metal's is `baseColor`.
 */
public float3 openPbrSpecularF0(float3 baseColor, float baseMetalness, float specularIor,
        float3 specularColor) {
    float amplitude = (specularIor - 1.0) / (specularIor + 1.0);
    return lerp(amplitude * amplitude * specularColor, baseColor, clamp(baseMetalness, 0.0, 1.0));
}

public float3 openPbrSpecularF0(MaterialInput material) {
    return openPbrSpecularF0(material.baseColor, material.baseMetalness, material.specularIor,
            material.specularColor);
}

public struct SurfaceInput {
    public MaterialInput material;
    public float3 position;
    public float3 geometricNormal;
    public float3 shadingNormal;
    public float3 outgoingDirection;
    public float2 textureCoordinate;
    public float textureFootprint;
    public float biomeTemperature;
    public float biomeHumidity;
    public uint surfaceFlags;
};

/**
 * What the estimator and the transport read. Unlike MaterialInput this is not authored vocabulary: it is
 * already resolved into the quantities a BSDF evaluates with, so reflectance is F0 and roughness is GGX
 * alpha (the square of MaterialInput.specularRoughness).
 */
public struct SurfaceClosure {
    public float3 shadingNormal;
    /** The whole diffuse response, before `transmission` splits it between the two hemispheres. */
    public float3 diffuseAlbedo;
    public float3 specularF0;
    public float alphaRoughness;
    public float3 emissionRadiance;
    /**
     * Fraction of the diffuse response leaving the far side of the surface. 0 is an ordinary opaque
     * surface, 0.5 is a symmetric thin scatterer that answers a light from either side identically —
     * which is what a billboard is — and 1 transmits everything.
     */
    public float transmission;
    public float interiorIor;
    public uint flags;
};

public struct BsdfQuery {
    public SurfaceClosure surface;
    public float3 outgoingDirection;
    public float3 incomingDirection;
};

public struct BsdfEvaluation {
    public float3 value;
    public float pdf;
    public uint eventFlags;
};

public struct BsdfSampleQuery {
    public SurfaceClosure surface;
    public float3 outgoingDirection;
    public float3 random;
};

public struct BsdfSample {
    public float3 incomingDirection;
    public float3 throughputWeight;
    public float pdf;
    public uint eventFlags;
};

public struct EnvironmentQuery {
    public float3 direction;
    public bool showCelestialBodies;
};
