module caustica_types;

// Extension colours and radiance are scene-linear ACEScg. Distances are scene world units and
// directions are normalized and point away from the shaded position.
public static const uint EVENT_NONE = 0x0u;
public static const uint EVENT_DIFFUSE = 0x1u;
public static const uint EVENT_GLOSSY = 0x2u;
public static const uint EVENT_SPECULAR = 0x4u;
public static const uint EVENT_REFLECTION = 0x8u;
public static const uint EVENT_TRANSMISSION = 0x10u;

/**
 * SurfaceInput.surfaceFlags: this hit is the one the reconstructor sees, so a surface whose normal
 * animates should also fill `previousGeometryNormal` — that pair is what a reflection reprojects with.
 * Unset on every other hit, where the previous normal is never read and evaluating it would be waste.
 */
public static const uint SURFACE_RECONSTRUCTION_ANCHOR = 0x1u;

/**
 * The authored surface description, a subset of OpenPBR Surface. Field names are the spec's, so a value
 * here means what the spec says it means — most of all `specularRoughness`, which is OpenPBR r and NOT
 * the GGX alpha the BSDF consumes (alpha = r * r).
 *
 * Normal-incidence reflectance is deliberately absent: OpenPBR derives it from `specularIor` and
 * `specularColor` for a dielectric and from `baseColor` for a metal. Use `openPbrSpecularF0`.
 */
public struct MaterialInput {
    /**
     * OpenPBR geometry_normal: the shading normal, already carrying the material's normal map. It is
     * writable because a procedural surface's normal IS its description — the engine's transport reads
     * this one for refraction, so perturbing it here is what makes a wave a wave.
     */
    public float3 geometryNormal;
    /**
     * `geometryNormal` one frame ago. Not an OpenPBR parameter: reconstruction reprojects a reflection
     * through the surface plane and needs to know where that plane was, which only the function that
     * produced the normal can answer. The engine pre-fills it with `geometryNormal`, which is the right
     * answer for everything that does not animate; fill it only when
     * SURFACE_RECONSTRUCTION_ANCHOR is set.
     */
    public float3 previousGeometryNormal;
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
     * OpenPBR emission_color and emission_luminance (cd/m²). A material adapter may derive emission
     * colour from its base texture, but that is source policy rather than part of this model.
     */
    public float3 emissionColor;
    public float emissionLuminance;
    /** OpenPBR geometry_opacity: coverage, i.e. whether the surface is present over this footprint. */
    public float geometryOpacity;
    /**
     * OpenPBR geometry_thin_walled: one surface with no interior — a leaf, a blade of grass, a billboard,
     * and equally an opaque shell whose interior is never transported through.
     * Glass is the opposite case: two interfaces with a real interior, so the engine refracts and pushes
     * a medium instead, and those surfaces never reach this description. It selects which transmission
     * `transmissionWeight` means.
     */
    public bool geometryThinWalled;
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

/**
 * Where the surface is being looked at, and the frame constants a procedural one evaluates against. The
 * description itself is not here: it arrives as the `inout MaterialInput` an implementation edits.
 */
public struct SurfaceInput {
    public float3 position;
    /** The triangle's own normal, oriented toward the viewer. Unperturbed by any normal map. */
    public float3 geometricNormal;
    public float3 outgoingDirection;
    public float2 textureCoordinate;
    public float textureFootprint;
    /**
     * A world-pinned, precision-safe position for procedural evaluation. It follows `position` while
     * compensating for scene-origin rebases, so a pattern remains fixed in all three scene dimensions.
     * Its modulus is an engine constant, so a very low-frequency field can alias across it where an
     * ordinary one does not.
     */
    public float3 proceduralPosition;
    /** Animation time in seconds, wrapped. Paired with `previousTime` so motion is derivable. */
    public float time;
    /** `time` one frame ago; equal to `time` when no adjacent frame exists to reproject from. */
    public float previousTime;
    /** SURFACE_* bits describing this hit. */
    public uint surfaceFlags;
};

/**
 * Exactly what the engine's BSDF evaluates with. Unlike MaterialInput this is not authored vocabulary
 * and nothing outside the engine produces one: reflectance is already F0 and roughness is already GGX
 * alpha (the square of MaterialInput.specularRoughness). It reaches an extension only as the surface
 * `evaluateResponse` is stylizing, and it is exactly the state the payload carries between stages.
 */
public struct SurfaceClosure {
    public float3 shadingNormal;
    /** The whole diffuse response, before `transmission` splits it between the two hemispheres. */
    public float3 diffuseAlbedo;
    public float3 specularF0;
    public float alphaRoughness;
    /** Emitted radiance in scene-linear ACEScg, after emission_color and luminance are combined. */
    public float3 emissionRadiance;
    /**
     * Fraction of the diffuse response leaving the far side of the surface. 0 is an ordinary opaque
     * surface, 0.5 is a symmetric thin scatterer that answers a light from either side identically —
     * which is what a billboard is — and 1 transmits everything.
     */
    public float transmission;
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
    /** Whether environment emitters already represented by direct-light sampling remain visible. */
    public bool showEnvironmentEmitters;
};
