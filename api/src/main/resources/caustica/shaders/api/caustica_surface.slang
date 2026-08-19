module caustica_surface;

import caustica_types;
import caustica_medium;

/**
 * A surface implementation supplies PARAMETERS, not lobes. The engine implements the OpenPBR BSDF once,
 * so the value/pdf agreement every lighting backend targets cannot be violated by an implementation
 * rather than merely being required of it.
 *
 * The cost of that narrowing is deliberate: a genuinely different lobe (hair, cloth, a coat) is engine
 * work, and OpenPBR already reserves the parameters for those. `evaluateResponse` remains the look hook.
 */
public interface ISurfaceModel {
    /**
     * Edit the description the engine decoded for this hit. Everything is already filled in from the
     * material record and its texture pages, so an implementation overrides only what it owns — an
     * animated normal, a procedurally computed emission — and leaves the rest alone.
     */
    public void evaluateSurface(SurfaceInput input, inout MaterialInput material) {}

    /** Applied once to a vertex's total shaded contribution. The reference look is identity. */
    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface) {
        return radiance;
    }

    /** Describe the interior bounded by this surface when it is transmissive. */
    public MediumProperties evaluateMedium(MediumInput input) {
        return referenceMedium(input);
    }

    /** Multiplicative direct-light response evaluated at a tracked boundary crossing. Neutral by default. */
    public float3 evaluateMediumLighting(MediumLightingInput input) {
        return float3(1.0);
    }
};

/**
 * The generated fan-out over every registered surface implementation, selected per material rather than
 * per composition. The engine names only this interface; the switch behind it is generated with the
 * composition root, so adding an implementation costs a case and no engine change.
 */
public interface ISurfaceDispatch {
    public void evaluateSurface(uint implementation, SurfaceInput input, inout MaterialInput material);
    public float3 evaluateResponse(uint implementation, float3 radiance, SurfaceClosure surface);
    public MediumProperties evaluateMedium(uint implementation, MediumInput input);
    public float3 evaluateMediumLighting(uint implementation, MediumLightingInput input);
};

/**
 * What the reconstruction guides need from a surface. Public and named on purpose: this is the contract
 * a lobe must be able to answer, not an engine-private derivation from OpenPBR fields. Blender's Cycles
 * builds its Ray Reconstruction guides by asking every closure for exactly this and summing the weighted
 * results; if the engine derived guides privately, a future closure-level API would have no way to feed
 * reconstruction, and that would only be discovered once someone tried to add one.
 */
public struct SurfaceGuide {
    /** Roughness-classified, NOT base_color: a rough glossy lobe belongs to the diffuse guide. */
    public float3 diffuseAlbedo;
    /** The complementary component: view-dependent integrated specular reflectivity, not raw F0. */
    public float3 specularAlbedo;
    /** Shading normal; also what transport reads for refraction. */
    public float3 normal;
    /** OpenPBR r = sqrt(GGX alpha) — the quantity Ray Reconstruction expects, not alpha. */
    public float perceptualRoughness;
};

// NVIDIA RR guide-buffer helper: integrated/view-dependent specular reflectivity, not raw F0.
// alpha is GGX alpha, NoV the view cosine at this hit.
float3 rrSpecularAlbedo(float3 specularReflectance, float alpha, float NoV) {
    NoV = abs(NoV);
    float NoV2 = NoV * NoV;
    float alpha2 = alpha * alpha;
    float4 X = float4(1.0, NoV, NoV2, NoV * NoV2);
    float4 Y = float4(1.0, alpha, alpha2, alpha * alpha2);

    float2 m1 = float2(
            dot(float2(0.99044, -1.28514), X.xy),
            dot(float2(1.29678, -0.755907), X.xy));
    float3 Xxyw = float3(X.x, X.y, X.w);
    float3 m2 = float3(
            dot(float3(1.0, 2.92338, 59.4188), Xxyw),
            dot(float3(20.3225, -27.0302, 222.592), Xxyw),
            dot(float3(121.563, 626.13, 316.627), Xxyw));
    float bias = dot(m1, Y.xy) / max(dot(m2, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    float2 m3 = float2(
            dot(float2(0.0365463, 3.32707), X.xy),
            dot(float2(9.0632, -9.04756), X.xy));
    float3 Xxzw = float3(X.x, X.z, X.w);
    float3 m4 = float3(
            dot(float3(1.0, 3.59685, -1.36772), Xxzw),
            dot(float3(9.04401, -16.3174, 9.22949), Xxzw),
            dot(float3(5.56589, 19.7886, -20.2123), Xxzw));
    float scale = dot(m3, Y.xy) / max(dot(m4, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    bias *= clamp(specularReflectance.g * 50.0, 0.0, 1.0);
    return specularReflectance * max(0.0, scale)
            + float3(max(0.0, bias), max(0.0, bias), max(0.0, bias));
}

// Where a glossy lobe stops reading as specular to the reconstructor and starts reading as diffuse.
// Diffuse and specular albedo are DENOISING categories, not BSDF types, so the crossing is a blend: a
// hard cutoff pops as a surface's roughness drifts across it, most visibly on a normal-mapped floor
// where neighbouring texels sit either side.
static const float GUIDE_SPECULAR_ROUGHNESS_MIN = 0.35;
static const float GUIDE_SPECULAR_ROUGHNESS_MAX = 0.8;

/**
 * The engine's canonical guide derivation. Implementations do not write guides themselves; when a
 * closure-level API arrives, its per-lobe version is a weighted sum over this same struct.
 */
public SurfaceGuide guideFor(SurfaceClosure surface, float3 viewDirection) {
    float alpha = clamp(surface.alphaRoughness, 0.0, 1.0);
    float perceptual = sqrt(alpha);
    float3 specular = rrSpecularAlbedo(surface.specularF0, alpha,
            dot(surface.shadingNormal, viewDirection));
    float specularWeight = 1.0 - smoothstep(GUIDE_SPECULAR_ROUGHNESS_MIN,
            GUIDE_SPECULAR_ROUGHNESS_MAX, perceptual);

    SurfaceGuide guide;
    guide.normal = surface.shadingNormal;
    guide.perceptualRoughness = perceptual;
    guide.specularAlbedo = specular * specularWeight;
    guide.diffuseAlbedo = surface.diffuseAlbedo + specular * (1.0 - specularWeight);
    return guide;
}
