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
    public float waterHitT;
};



// Views onto the module-level payload's hand-packed surface words (see Payload.surface0..2). Hit shaders
// take their payload as a parameter and call the world_common forms directly; these exist so raygen-side
// code reads the same as the payloadRoughness()/payloadEmission() accessors below it.
public float3 payloadAlbedo() { return unpackAlbedo(payload); }
public float3 payloadNormal() { return unpackNormal(payload); }
/** Sky radiance on a miss — absolute scene units, full fp32. Only meaningful when hitT < 0. */
public float3 payloadSky() { return unpackSky(payload); }

public uint payloadMaterial() { return payload.flags & PAYLOAD_MATERIAL_MASK; }
// Set by world.rchit on any dielectric hit (see PAYLOAD_DIELECTRIC_ENTERING) — whether the ray was
// travelling into the volume (vs. out of it) at this hit's face.
public bool payloadDielectricEntering() { return (payload.flags & PAYLOAD_DIELECTRIC_ENTERING) != 0u; }
public bool payloadEmitterInList() { return (payload.flags & PAYLOAD_EMITTER_IN_LIST) != 0u; }
// OpenPBR specular_roughness: PERCEPTUAL, not GGX alpha. This is the one convention used end to end —
// the material record, the canonical pages and this payload lane all carry r, and the GGX alpha every
// lobe wants is r*r, squared where the lobe is evaluated. ggxD/ggxG1/sampleGGXVNDF/rrSpecularAlbedo all
// take alpha, so a value handed to one of them is squared first; DLSS-RR wants r and gets it unsquared.
public float payloadRoughness() { return unpackHalf2(payload.roughMetal).x; }
/** GGX alpha for this hit: the payload's perceptual roughness squared. */
public float payloadAlphaRoughness() {
    float roughness = clamp(payloadRoughness(), 0.0, 1.0);
    return roughness * roughness;
}
public float payloadMetalness() { return unpackHalf2(payload.roughMetal).y; }
public float payloadEmission() { return unpackHalf2(payload.emission).x; }
public float payloadIor() { return unpackHalf2(payload.iorTransmission).x; }
public float payloadTransmission() { return unpackHalf2(payload.iorTransmission).y; }

public static const float PI = 3.14159265359;
public static const float INV_PI = 0.31830988618;

// The sky (atmosphere LUT + sun/moon discs + stars) is evaluated in world.rmiss. On a miss, raygen reads
// payloadSky() and accumulates that radiance.
//
// celestialLight is the single directional light NEE samples, and it is a HAND-AUTHORED FIXED SUN, not a
// per-frame value. Nothing produces a celestial light: a sky extension submits sun and moon through
// LightProvider.submitLights, and the engine consuming that submission is what will replace this
// constant. Until then this light does not track Minecraft's time of day — it stays lit at night, and its
// direction is independent of the sun disc the sky slot draws, so the two only agree at noon.
public static CelestialLight celestialLight = {
    // Minecraft's noon sun under SkyLutPass's default 30° south tilt, i.e. celestialDirection(0, π/6).
    // Points TOWARD the light: every consumer uses it as both dot(n, dir) and a shadow-ray direction.
    float3(0.0, 0.86602540, 0.5),
    // Direct normal illuminance at ground level, ACEScg. The look package's 128,000 lux is top of
    // atmosphere and the sky LUT applies the atmosphere's transmittance to it; nothing applies that
    // transmittance to this light, so it carries the surface figure instead. BT.709 white maps to ACEScg
    // white (the matrix rows sum to one), so a neutral sun is equal across the three channels.
    float3(100000.0, 100000.0, 100000.0),
    // SkyLutPass's default sky.sun-angular-radius-degrees, 0.6° — deliberately wider than the real sun's
    // 0.267° so sampleSquare's single sample per vertex yields visible contact-hardening penumbrae.
    0.010471976
};
public static const float SURF_BIAS = 0.005;  // offset secondary-ray origins along the normal (anti-acne)
public static const float RAY_TMIN = 0.001;   // tiny tmin: the normal offset already clears the surface

// Water is a smooth dielectric. IOR 1.333; absorption is per-block Beer-Lambert extinction.
public static const float WATER_IOR = 1.333;
/**
 * The distant celestial light every NEE call site reads from. Every consumer gates on {@code ndl} and
 * illuminance, so a zero-illuminance light fires no shadow ray.
 */
public struct CelestialLight {
    public float3 dir;          // unit, toward the light
    public float3 illuminance;  // linear ACEScg (the transport basis), lux at the viewer
    public float  halfAngle;    // radians; <= 0 samples the exact direction, giving hard shadows
};

public static const float3 SKY_SPEC_ALBEDO = float3(0.5, 0.5, 0.5); // RR guide default for sky pixels.
public static const float3 SKY_DIFF_ALBEDO = float3(0.5, 0.5, 0.5);

// Transmitted-ray origin offset when crossing a face from the TRANSLUCENT terrain layer (glass, ice).
// Must be SMALLER than the mesher-side TRANSLUCENT_INSET (RtTerrainMesher, 2e-4 blocks): those quads are
// recessed into their block by that inset, so a face touching a slab/stair leaves a tiny gap before the
// neighbour's surface. A large bias (SURF_BIAS) would restart the ray past that neighbour and see
// through it; this lands the ray inside the gap so the neighbour is still hit, while still clearing the
// surface it left to avoid self-intersection. Water is meshed by RtFluidMesher with no inset, so it uses
// the ordinary bias.
public static const float INSET_TRANSMIT_BIAS = 1.0e-4;

// Ray-cone texture LOD: raygen tracks a one-pixel footprint along the path, and closest-hit converts it
// to texture mip levels from the local UV/world-space scale. Diffuse/glossy bounces widen the cone so
// indirect texture fetches don't keep sampling mip 0 through broad lobes.
public static const float RAY_CONE_MIN_SPREAD = 1.0e-5;
public static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
public static const float RAY_CONE_DIFFUSE_SPREAD = 0.25;
public static const float RAY_CONE_GLOSSY_SPREAD_SCALE = 0.35;

// Stored roughness is perceptual (see payloadRoughness), so these are perceptual thresholds.
public static const float MIRROR_ROUGHNESS_MAX = 0.02; // LabPBR perceptual smoothness >= 0.98
// Above this the specular lobe is too broad for a single mirrored reprojection to describe, so the
// reflection MV is left at zero and RR falls back to the ordinary MV.
public static const float SPEC_MOTION_ROUGHNESS_MAX = 0.5; // perceptual smoothness <= 0.5

public bool isDeltaRoughness(float roughness) {
    return clamp(roughness, 0.0, 1.0) <= MIRROR_ROUGHNESS_MAX;
}

// Continuations one sample may carry: the camera segment, plus the reflection branch a primary
// dielectric splits off. Raising this needs a real queue, which is what the continuation buffer is for.
public static const uint MAX_PATH_SEGMENTS = 2u;
