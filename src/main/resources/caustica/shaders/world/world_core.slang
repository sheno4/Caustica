// Bindings, per-frame push state, the ray payload, and the constants every pass shares.
// Import first: everything below depends on pc, worldPush and payload.

module world_core;

import world_common;
import bindings;

// Per-frame push data loaded once from the BDA buffer at the top of main() (same pattern as the GLSL
// `worldPush` global). Layout constants generated from this module's SPIR-V — see world_common.WorldPush.
public static WorldPush worldPush = {};

// The radiance payload remains module-level so tracePath and its guide helpers can share it across
// HitObject trace/invoke calls, mirroring the GLSL rayPayloadEXT global.
public static Payload payload = {};

public struct VisibilityResult {
    public float3 transmittance;
    public float boundaryHitT;
};



// Views onto the module-level payload's hand-packed surface words (see Payload.surface0..2). Hit shaders
// take their payload as a parameter and call the world_common forms directly; these exist so raygen-side
// code reads the same as the payloadRoughness()/payloadEmissionRadiance() accessors below it.
public float3 payloadAlbedo() { return unpackAlbedo(payload); }
/**
 * The SHADING normal: the material's normal map and any procedural perturbation are already applied, so
 * transport refracts through the same normal the shading and the guides see. Nothing downstream
 * re-derives it.
 */
public float3 payloadNormal() { return unpackNormal(payload); }
/** {@link payloadNormal} one frame ago. Only resolved for a PAYLOAD_PRIMARY_RAY hit. */
public float3 payloadPreviousNormal() { return unpackNormalOct(payload.previousNormal); }
/** Sky radiance on a miss — absolute scene units, full fp32. Only meaningful when hitT < 0. */
public float3 payloadSky() { return unpackSky(payload); }

// Named payloadDielectric rather than payloadTransmissive to stay unambiguous next to
// payloadTransmission() below — a bool ("does this hit route through the dielectric transport") vs. that
// function's OpenPBR transmission_weight float. Forwards MaterialBinding.flags & BINDING_TRANSMISSIVE.
public bool payloadDielectric() { return (payload.flags & PAYLOAD_TRANSMISSIVE) != 0u; }
// Set by world.rchit on any dielectric hit (see PAYLOAD_DIELECTRIC_ENTERING) — whether the ray was
// travelling into the volume (vs. out of it) at this hit's face.
public bool payloadDielectricEntering() { return (payload.flags & PAYLOAD_DIELECTRIC_ENTERING) != 0u; }
/** The registered surface implementation this hit resolved through; 0 is the built-in surface. */
public uint payloadSurfaceImpl() { return payload.flags >> PAYLOAD_SURFACE_IMPL_SHIFT; }
// OpenPBR specular_roughness: PERCEPTUAL, not GGX alpha. This is the one convention used end to end —
// the material record, the canonical pages and this payload lane all carry r, and the GGX alpha every
// lobe wants is r*r, squared where the lobe is evaluated. ggxD/ggxG1/sampleGGXVNDF all take alpha, so a
// value handed to one of them is squared first; DLSS-RR wants r and gets it unsquared.
public float payloadRoughness() { return unpackHalf2(payload.roughEmissionB).x; }
/** GGX alpha for this hit: the payload's perceptual roughness squared. */
public float payloadAlphaRoughness() {
    float roughness = clamp(payloadRoughness(), 0.0, 1.0);
    return roughness * roughness;
}
public float3 payloadEmissionRadiance() {
    return float3(unpackHalf2(payload.emissionRG), unpackHalf2(payload.roughEmissionB).y);
}
public bool payloadEmissive() { return any(payloadEmissionRadiance() > 0.0); }
public float payloadIor() { return unpackHalf2(payload.iorTransmission).x; }
public float payloadTransmission() { return unpackHalf2(payload.iorTransmission).y; }
public float3 payloadDiffuseTransmissionAlbedo() {
    return float3(payload.diffuseTransmissionAlbedo);
}
public float3 payloadThinTransmissionColor() { return float3(payload.thinTransmissionColor); }
public float payloadThinTransmissionWeight() { return unpackHalf2(payload.iorTransmission).x; }

public static const float PI = 3.14159265359;
public static const float INV_PI = 0.31830988618;

public static const float SURF_BIAS = 0.005;  // offset secondary-ray origins along the normal (anti-acne)
public static const float RAY_TMIN = 0.001;   // tiny tmin: the normal offset already clears the surface
public static const float RAY_TMAX = 1.0e20;  // scale-independent bound for rays toward the environment

public static const float3 SKY_SPEC_ALBEDO = float3(0.5, 0.5, 0.5); // RR guide default for sky pixels.
public static const float3 SKY_DIFF_ALBEDO = float3(0.5, 0.5, 0.5);

// Small origin offset for continuing a guide through a coverage hole. It clears the accepted triangle
// without skipping a nearby surface that should become the guide endpoint.
public static const float COVERAGE_CONTINUATION_BIAS = 1.0e-4;

// Ray-cone texture LOD: raygen tracks a one-pixel footprint along the path, and closest-hit converts it
// to texture mip levels from the local UV/world-space scale. Diffuse/glossy bounces widen the cone so
// indirect texture fetches don't keep sampling mip 0 through broad lobes.
public static const float RAY_CONE_MIN_SPREAD = 1.0e-5;
public static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
public static const float RAY_CONE_DIFFUSE_SPREAD = 0.25;
public static const float RAY_CONE_GLOSSY_SPREAD_SCALE = 0.35;

// Stored roughness is perceptual (see payloadRoughness), so these are perceptual thresholds.
public static const float MIRROR_ROUGHNESS_MAX = 0.02;
// Above this the specular lobe is too broad for a single mirrored reprojection to describe, so the
// reflection MV is left at zero and RR falls back to the ordinary MV.
public static const float SPEC_MOTION_ROUGHNESS_MAX = 0.5; // perceptual smoothness <= 0.5

// Continuations one sample may carry: the camera segment, plus the reflection branch a primary
// dielectric splits off. Raising this needs a real queue, which is what the continuation buffer is for.
public static const uint MAX_PATH_SEGMENTS = 2u;
