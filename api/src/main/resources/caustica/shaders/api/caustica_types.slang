module caustica_types;

/** Must equal dev.comfyfluffy.caustica.api.program.ProgramAbi.VERSION. */
public static const uint CAUSTICA_SHADER_ABI_VERSION = 2u;

/** MeshBuild.GeometrySemantic.PROJECTED_SURFACE_MODIFIER_RECEIVER. */
public static const uint GEOMETRY_PROJECTED_SURFACE_MODIFIER_RECEIVER = 0x1u;

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
 * animates should also fill `previous_geometry_normal` — that pair is what a reflection reprojects with.
 * Unset on every other hit, where the previous normal is never read and evaluating it would be waste.
 */
public static const uint SURFACE_RECONSTRUCTION_ANCHOR = 0x1u;
/** Enables optional time-varying procedural surface and medium evaluation for this frame. */
public static const uint SURFACE_ANIMATION_ENABLED = 0x2u;

/**
 * Stable pushed root available to every extension shader stage. `compositionData` addresses the generated
 * immutable table of extension roots. Descriptor heaps are SPIR-V built-ins and therefore need no address
 * in pushed data; typed indices and lookup helpers live in `caustica_resources`.
 */
public struct ShaderRootData {
    public uint abiVersion;
    public uint reserved;
    public uint64_t compositionData;
};

/** Three rows of an affine object-to-scene transform. Translation occupies each row's fourth component. */
public struct AffineTransform {
    public float4 row0;
    public float4 row1;
    public float4 row2;
};

/** Inverse-transpose linear transform used for object-space normals. */
public struct NormalTransform {
    public float3 row0;
    public float3 row1;
    public float3 row2;
};

public float3 transformPosition(AffineTransform transform, float3 position) {
    float4 homogeneous = float4(position, 1.0);
    return float3(dot(transform.row0, homogeneous), dot(transform.row1, homogeneous),
            dot(transform.row2, homogeneous));
}

public float3 transformDirection(AffineTransform transform, float3 direction) {
    return float3(dot(transform.row0.xyz, direction), dot(transform.row1.xyz, direction),
            dot(transform.row2.xyz, direction));
}

public float3 transformNormal(NormalTransform transform, float3 normal) {
    return float3(dot(transform.row0, normal), dot(transform.row1, normal), dot(transform.row2, normal));
}

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
 * The OpenPBR Surface description a surface implementation produces and the engine's BSDF consumes.
 *
 * Not an input to the implementation: the engine uploads no material record and passes nothing in.
 * `SurfaceInput` is what the implementation is given; this is what it returns.
 *
 * Every field carries the specification's own default, so an implementation declares an
 * `OpenPbrSurface` and writes only what it actually varies. Both `OpenPbrSurface material;` and
 * `OpenPbrSurface material = {};` yield the defaults.
 *
 * Field names, grouping, and declaration order are the specification's, verbatim, so a parameter here
 * and a parameter in an `open_pbr_surface` node are the same thing under the same name. A value means
 * exactly what the spec says it means — most of all `specular_roughness`, which is OpenPBR r and NOT the
 * GGX alpha the BSDF consumes (alpha = r * r).
 *
 * Unsupported groups are absent rather than present-and-ignored: there is no fuzz, coat, or thin film
 * here because the engine's BSDF has no lobe for them. Normal-incidence reflectance is likewise absent
 * because OpenPBR derives it from `specular_ior` for a dielectric and `base_color` for a metal — use
 * `openPbrSpecularF0`.
 */
public struct OpenPbrSurface {
    // Base
    public float3 base_color = float3(0.8, 0.8, 0.8);
    public float base_metalness = 0.0;

    // Specular
    /** OpenPBR specular_roughness: perceptual. GGX alpha is its square. */
    public float specular_roughness = 0.3;
    public float specular_ior = 1.5;

    // Transmission
    /**
     * OpenPBR transmission_weight: the mix between the opaque dielectric base and the translucent base.
     * A volume routes the latter through the engine's refractive medium transport. A thin wall resolves
     * it as an absorbing, non-scattering dielectric sheet with a straight-through rough BTDF.
     */
    public float transmission_weight = 0.0;
    /**
     * OpenPBR transmission_color. The supported thin-sheet model treats it as normal-incidence
     * transmittance through the sheet.
     */
    public float3 transmission_color = float3(1.0, 1.0, 1.0);

    // Subsurface
    /** OpenPBR subsurface_weight: the mix between glossy-diffuse and subsurface slabs. */
    public float subsurface_weight = 0.0;
    /** OpenPBR subsurface_color: the dense scattering sheet's observed colour. */
    public float3 subsurface_color = float3(0.8, 0.8, 0.8);
    /**
     * OpenPBR subsurface_scatter_anisotropy. In thin-walled mode it divides subsurface energy between
     * diffuse reflection and diffuse transmission exactly as the specification defines.
     */
    public float subsurface_scatter_anisotropy = 0.0;

    // Emission
    /** OpenPBR emission_luminance, in cd/m². */
    public float emission_luminance = 0.0;
    public float3 emission_color = float3(1.0, 1.0, 1.0);

    // Geometry
    /**
     * OpenPBR geometry_thin_walled. A transmissive thin wall is a two-dimensional sheet and does not
     * change the path's medium stack. A non-thin-walled transmissive surface bounds the medium returned by
     * its implementation's evaluateMedium method.
     */
    public bool geometry_thin_walled = false;
    /**
     * OpenPBR geometry_normal: the shading normal, already carrying the material's normal map. It is
     * written rather than read because a procedural surface's normal IS its description — the engine's
     * transport reads this one for refraction, so perturbing it is what makes a wave a wave.
     *
     * Zero, the default, means "the triangle's own normal": the engine substitutes
     * `SurfaceInput.geometricNormal` after the implementation returns, so a surface with no normal map
     * writes nothing here.
     */
    public float3 geometry_normal = float3(0.0);
    /**
     * `geometry_normal` one frame ago. NOT an OpenPBR parameter: reconstruction reprojects a reflection
     * through the surface plane and needs to know where that plane was, which only the function that
     * produced the normal can answer. Zero, the default, means "unchanged since last frame" and resolves
     * to `geometry_normal`; write it only when SURFACE_RECONSTRUCTION_ANCHOR is set and the normal
     * animates.
     */
    public float3 previous_geometry_normal = float3(0.0);
};

/**
 * OpenPBR normal-incidence reflectance. Not an authored parameter: a dielectric's follows from
 * `specular_ior`; a metal's is `base_color`. OpenPBR specular_color is fixed at its white default in the
 * supported subset because its conductor F82 edge-tint semantics are not implemented.
 */
public float3 openPbrSpecularF0(float3 baseColor, float baseMetalness, float specularIor) {
    float amplitude = (specularIor - 1.0) / (specularIor + 1.0);
    return lerp(float3(amplitude * amplitude), baseColor, clamp(baseMetalness, 0.0, 1.0));
}

public float3 openPbrSpecularF0(OpenPbrSurface material) {
    return openPbrSpecularF0(material.base_color, material.base_metalness, material.specular_ior);
}

/**
 * Where the surface is being looked at, and the frame constants a procedural one evaluates against. The
 * description itself is not here: the implementation produces it as the `inout OpenPbrSurface` it fills.
 */
public struct SurfaceInput {
    /** Versioned pushed roots and descriptor-heap address-table root for this composition. */
    public ShaderRootData shaderRoot;
    /**
     * The material's word, copied verbatim from MaterialDefinition. Sixty-four bits so it can simply be a
     * device address: a material's parameters are whatever buffer the source pointed it at, and there is
     * no size beyond which an extension has to start packing bits.
     */
    public uint64_t materialData;
    /**
     * This geometry's word, copied verbatim from MeshBuild.Geometry. Normally the address of the geometry's
     * own attribute slice — per geometry rather than per mesh precisely so that
     * `attributes[primitiveIndex]` is correct with no base to add.
     */
    public uint64_t geometryData;
    /**
     * This placement's word, copied verbatim from SetInstance. What lets one mesh be placed many times and
     * still shade differently: a team colour, an animation frame, an index into a buffer the source
     * published. Shading only — geometry that differs per placement is a different mesh.
     */
    public uint64_t instanceData;
    /**
     * Index of the hit triangle within its geometry. Geometry-local: it restarts at zero for each
     * geometry, which is what makes `geometryData` a usable base pointer.
     */
    public uint primitiveIndex;
    /** Weights for triangle vertices one and two; vertex zero has weight `1 - x - y`. */
    public float2 barycentrics;
    /** Current object-to-scene transform for positions and tangent vectors. */
    public AffineTransform objectToScene;
    /** Previous-frame object-to-scene transform, equal to current when no history exists. */
    public AffineTransform previousObjectToScene;
    /** Inverse-transpose object-to-scene transform for normal-map vectors. */
    public NormalTransform normalToScene;
    public float3 position;
    /** The triangle's own normal, oriented toward the viewer. Unperturbed by any normal map. */
    public float3 geometricNormal;
    public float3 outgoingDirection;
    /**
     * Ray footprint width at the hit, in scene world units. Engine-produced from the ray cone, which is
     * the one filtering fact an extension cannot derive for itself — everything downstream of it, the
     * texture coordinates and the mip choice included, is the extension's own.
     */
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
    public ShaderRootData shaderRoot;
    /** Hit position in the current scene-origin coordinate system. */
    public float3 position;
    /** The oriented triangle normal, before material or procedural perturbation. */
    public float3 geometricNormal;
    /** Ray footprint width at the hit, in scene world units. */
    public float textureFootprint;
    /** The same three uninterpreted words `SurfaceInput` carries, and the same geometry-local index. */
    public uint64_t materialData;
    public uint64_t geometryData;
    public uint64_t instanceData;
    public uint primitiveIndex;
    public float2 barycentrics;
    /** GEOMETRY_* bits copied from MeshBuild.Geometry. */
    public uint geometrySemantics;
};

/**
 * Exactly what the engine's BSDF evaluates with. Unlike OpenPbrSurface this is not authored vocabulary
 * and nothing outside the engine produces one: reflectance is already F0 and roughness is already GGX
 * alpha (the square of OpenPbrSurface.specular_roughness). It reaches an extension only as the surface
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
    public ShaderRootData shaderRoot;
    public float3 direction;
    /** Whether environment emitters already represented by direct-light sampling remain visible. */
    public bool showEnvironmentEmitters;
    /**
     * The word the scene carried when it named this implementation, copied verbatim. The engine assigns it
     * no meaning: it is whatever the extension put there — a bindless slot, packed bits, or the address of
     * a buffer holding this scene's atmosphere.
     */
    public uint64_t parameters;
};
