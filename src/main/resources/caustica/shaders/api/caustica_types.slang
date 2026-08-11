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
/** Enables optional time-varying procedural surface and medium evaluation for this frame. */
public static const uint SURFACE_ANIMATION_ENABLED = 0x2u;

/** The material values available when a transmissive surface creates its interior medium. */
public struct MediumInput {
    public float3 sourceColor;
    public float specularIor;
    public float transmissionWeight;
};

public static const uint MEDIUM_TERMINATE_ON_MISS = 0x1u;
public static const uint MEDIUM_BOUNDARY_LIGHTING = 0x2u;

/** Host-neutral transport properties produced by the material implementation at an interface. */
public struct MediumProperties {
    public float3 extinction;
    /** Filter used only while reconstruction guides walk into the medium. */
    public float3 guideTransmittance;
    public float transmissionRayBias;
    /** MEDIUM_* bits retained with the path's medium stack. */
    public uint flags;
};

/** Inputs for a medium's optional multiplicative direct-light response at its exit boundary. */
public struct MediumLightingInput {
    public float3 boundaryPosition;
    public float3 proceduralPosition;
    public float3 lightDirection;
    public float boundaryDistance;
    public float mediumIor;
    public float time;
    public uint surfaceFlags;
};

/**
 * The authored surface description, a subset of OpenPBR Surface. Field names are the spec's, so a value
 * here means what the spec says it means — most of all `specularRoughness`, which is OpenPBR r and NOT
 * the GGX alpha the BSDF consumes (alpha = r * r).
 *
 * Normal-incidence reflectance is deliberately absent: OpenPBR derives it from `specularIor` and
 * `specularIor` for a dielectric and from `baseColor` for a metal. Use `openPbrSpecularF0`.
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
    /**
     * OpenPBR transmission_weight: the mix between the opaque dielectric base and the translucent base.
     * A volume routes the latter through the engine's refractive medium transport. A thin wall resolves
     * it as an absorbing, non-scattering dielectric sheet with a straight-through rough BTDF.
     */
    public float transmissionWeight;
    /**
     * OpenPBR transmission_color. The supported thin-sheet model treats it as normal-incidence
     * transmittance through the sheet. Volume colour remains a geometry-source input to the medium hook.
     */
    public float3 transmissionColor;
    /** OpenPBR subsurface_weight: the mix between glossy-diffuse and subsurface slabs. */
    public float subsurfaceWeight;
    /** OpenPBR subsurface_color: the dense scattering sheet's observed colour. */
    public float3 subsurfaceColor;
    /**
     * OpenPBR subsurface_scatter_anisotropy. In thin-walled mode it divides subsurface energy between
     * diffuse reflection and diffuse transmission exactly as the specification defines.
     */
    public float subsurfaceScatterAnisotropy;
    /**
     * OpenPBR emission_color and emission_luminance (cd/m²). A material adapter may derive emission
     * colour from its base texture, but that is source policy rather than part of this model.
     */
    public float3 emissionColor;
    public float emissionLuminance;
};

/**
 * OpenPBR normal-incidence reflectance. Not an authored parameter: a dielectric's follows from
 * `specularIor`; a metal's is `baseColor`. OpenPBR specular_color is fixed at its white default in the
 * supported subset because its conductor F82 edge-tint semantics are not implemented.
 */
public float3 openPbrSpecularF0(float3 baseColor, float baseMetalness, float specularIor) {
    float amplitude = (specularIor - 1.0) / (specularIor + 1.0);
    return lerp(float3(amplitude * amplitude), baseColor, clamp(baseMetalness, 0.0, 1.0));
}

public float3 openPbrSpecularF0(MaterialInput material) {
    return openPbrSpecularF0(material.baseColor, material.baseMetalness, material.specularIor);
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

/** Geometry and footprint inputs shared by projected surface modifiers. */
public struct SurfaceModifierInput {
    /** Hit position in the current scene-origin coordinate system. */
    public float3 position;
    /** The oriented triangle normal, before material or procedural perturbation. */
    public float3 geometricNormal;
    /** Ray footprint width at the hit, in scene world units. */
    public float textureFootprint;
};

/**
 * Exactly what the engine's BSDF evaluates with. Unlike MaterialInput this is not authored vocabulary
 * and nothing outside the engine produces one: reflectance is already F0 and roughness is already GGX
 * alpha (the square of MaterialInput.specularRoughness). It reaches an extension only as the surface
 * `evaluateResponse` is stylizing, and it is exactly the state the payload carries between stages.
 */
public struct SurfaceClosure {
    public float3 shadingNormal;
    /** Sum of diffuse reflection and transmission albedos; used directly by reconstruction guides. */
    public float3 diffuseAlbedo;
    /** The diffuse part of `diffuseAlbedo` that leaves through the far hemisphere. */
    public float3 diffuseTransmissionAlbedo;
    public float3 specularF0;
    public float alphaRoughness;
    /** Emitted radiance in scene-linear ACEScg, after emission_color and luminance are combined. */
    public float3 emissionRadiance;
    /** OpenPBR transmission_color used by the thin dielectric sheet. */
    public float3 thinTransmissionColor;
    /** OpenPBR transmission_weight after the dielectric share of the base has been applied. */
    public float thinTransmissionWeight;
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
