// Direct lighting: the emitter light grid, RIS reservoirs, and reservoir shading.
// INDIRECT PASS ONLY — the primary pass shades nothing. Depends on trace and math.

// contribution weight, M the effective sample count. wSum/phat are streaming scratch (not persisted).

module lighting;

import world_common;
import world_core;
import bindings;
import math;
import medium;
import trace;
import caustica_surface;
import caustica_types;

public struct Reservoir {
    public float3 pos;  // light sample point (rebased world space)
    public float3 lnrm; // emitter outward normal, or sampled toward-source direction for distant lights
    public float3 le;   // emitter radiance/intensity, or integrated illuminance for distant lights
    public float area;  // emitter rectangle area (the area-light pdf factor)
    public float M;
    public float W;
    public float wSum;
    public float phat;
    public uint lightType; // 0 rectangle, 1 point, 2 spot, 3 distant
    public float shapeScalar; // cos(outer angular radius) for spot and distant
    public float metersPerWorldUnit;
};

public Reservoir resEmpty() {
    Reservoir r;
    r.pos = float3(0.0, 0.0, 0.0);
    r.lnrm = float3(0.0, 0.0, 0.0);
    r.le = float3(0.0, 0.0, 0.0);
    r.area = 0.0;
    r.M = 0.0;
    r.W = 0.0;
    r.wSum = 0.0;
    r.phat = 0.0;
    r.lightType = 0u;
    r.shapeScalar = -1.0;
    r.metersPerWorldUnit = 1.0;
    return r;
}

// Delta-light contribution for point and constant-intensity spot emitters. Intensity is candela and
// distance is in world metres, so intensity/dist² is incident illuminance at the receiver.
public float3 evalDeltaLightContrib(float3 lightPos, float3 lightDirection, float3 intensity,
                            float range, float outerCos, float metersPerWorldUnit, uint lightType,
                            float3 hitPos, float3 n, float3 v, float3 diffAlb, float3 F0,
                            float rough, float transmission, out float phat) {
    phat = 0.0;
    float3 toL = lightPos - hitPos;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-6 || dist2 > range * range) return float3(0.0);
    float3 wi = toL * rsqrt(dist2);
    if (lightType == 2u && dot(lightDirection, -wi) < outerCos) return float3(0.0);
    float ndl = dot(n, wi);
    float3 response = float3(0.0);
    if (ndl > 0.0) {
        float3 h = normalize(wi + v);
        float ndh = max(0.0, dot(n, h));
        float ndv = max(1.0e-4, dot(n, v));
        float vdh = max(0.0, dot(v, h));
        float D = ggxD(ndh, rough);
        float Gs = ggxG1(ndv, rough) * ggxG1(ndl, rough);
        float3 Fs = fresnelSchlick(vdh, F0);
        float3 layerTransmission = glossyLayerTransmission(F0, ndv, ndl);
        float3 brdf = diffAlb * layerTransmission * (1.0 - transmission) * INV_PI
                + (D * Gs) * Fs / (4.0 * ndv * ndl);
        float dist2Meters = dist2 * metersPerWorldUnit * metersPerWorldUnit;
        response = brdf * intensity * (ndl / dist2Meters);
    } else if (transmission > 0.0) {
        float backNdl = -ndl;
        float3 layerTransmission = glossyLayerTransmission(F0, max(0.0, dot(n, v)), backNdl);
        float dist2Meters = dist2 * metersPerWorldUnit * metersPerWorldUnit;
        response = diffAlb * layerTransmission * transmission * INV_PI * intensity
                * (backNdl / dist2Meters);
    }
    phat = luminance(response);
    return response;
}

// A distant descriptor carries total normal illuminance integrated over a uniform-radiance cone. The
// uniform solid-angle proposal cancels its radiance into E*2/(1+cos(alpha)); the factor tends to one for
// an exact directional source. This keeps the continuous angular PDF out of the RIS population PDF.
public float3 evalDistantLightContrib(float3 lightDirection, float3 illuminance, float outerCos,
                              float3 n, float3 v, float3 diffAlb, float3 F0, float rough,
                              float transmission, out float phat) {
    phat = 0.0;
    float ndl = dot(n, lightDirection);
    float3 response = float3(0.0);
    float angularWeight = 2.0 / (1.0 + outerCos);
    if (ndl > 0.0) {
        float3 h = normalize(lightDirection + v);
        float ndh = max(0.0, dot(n, h));
        float ndv = max(1.0e-4, dot(n, v));
        float vdh = max(0.0, dot(v, h));
        float D = ggxD(ndh, rough);
        float Gs = ggxG1(ndv, rough) * ggxG1(ndl, rough);
        float3 Fs = fresnelSchlick(vdh, F0);
        float3 layerTransmission = glossyLayerTransmission(F0, ndv, ndl);
        float3 brdf = diffAlb * layerTransmission * (1.0 - transmission) * INV_PI
                + (D * Gs) * Fs / (4.0 * ndv * ndl);
        response = brdf * illuminance * (ndl * angularWeight);
    } else if (transmission > 0.0) {
        float backNdl = -ndl;
        float3 layerTransmission = glossyLayerTransmission(F0, max(0.0, dot(n, v)), backNdl);
        response = diffAlb * layerTransmission * transmission * INV_PI * illuminance
                * (backNdl * angularWeight);
    }
    phat = luminance(response);
    return response;
}

public float3 sampleUniformCone(float3 axis, float outerCos, inout uint seed) {
    if (outerCos >= 0.9999999) return axis;
    float cosTheta = lerp(outerCos, 1.0, rndf(seed));
    float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
    float phi = 2.0 * PI * rndf(seed);
    float3 reference = abs(axis.y) < 0.999 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
    float3 tangent = normalize(cross(reference, axis));
    float3 bitangent = cross(axis, tangent);
    return normalize(axis * cosTheta + tangent * (sinTheta * cos(phi))
            + bitangent * (sinTheta * sin(phi)));
}

// Unshadowed contribution of a fixed light sample point at surface (hitPos,n,v); out p-hat = its
// luminance (the resampling target). Evaluated from the UNBIASED hitPos — SURF_BIAS is applied only to
// the shadow-ray origin in shadeReservoir, on whichever side the survivor sample lands.
//
// The engine's reference target function, matching the built-in BSDF's two hemispheres: a candidate in
// front of the surface gets the Lambert(+GGX) reflection split, one behind it gets the thin wall's
// diffuse transmission. `transmission` is what divides the diffuse response between them, so a billboard
// at one half answers a light from either side identically and an opaque surface at zero answers a light
// behind it with nothing.
public float3 evalSampleContrib(float3 sp, float3 lnrm, float3 le, float area, float3 hitPos, float3 n,
                         float3 v, float3 rd, float3 diffAlb, float3 F0, float rough,
                         float transmission, out float phat) {
    phat = 0.0;
    float3 toL = sp - hitPos;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-6) {
        return float3(0.0, 0.0, 0.0);
    }
    float dist = sqrt(dist2);
    float3 wi = toL / dist;
    float cosL = max(0.0, dot(lnrm, -wi));
    if (cosL <= 0.0) {
        return float3(0.0, 0.0, 0.0); // receiver behind the emitter face
    }
    float ndl = dot(n, wi);
    float3 contrib = float3(0.0, 0.0, 0.0);
    if (ndl > 0.0) {
        float G = ndl * cosL / dist2; // area-light geometry term (x area)
        float3 h = normalize(wi + v);
        float ndh = max(0.0, dot(n, h));
        float ndv = max(1.0e-4, dot(n, v));
        float vdh = max(0.0, dot(v, h));
        float D = ggxD(ndh, rough);
        float Gs = ggxG1(ndv, rough) * ggxG1(ndl, rough);
        float3 Fs = fresnelSchlick(vdh, F0);
        float3 layerTransmission = glossyLayerTransmission(F0, ndv, ndl);
        float3 brdf = diffAlb * layerTransmission * (1.0 - transmission) * INV_PI
                + (D * Gs) * Fs / (4.0 * ndv * ndl);
        contrib = brdf * le * (G * area);
    } else if (transmission > 0.0) {
        float backNdl = -ndl;
        float G = backNdl * cosL / dist2;
        float3 layerTransmission = glossyLayerTransmission(F0, max(0.0, dot(n, v)), backNdl);
        contrib = diffAlb * layerTransmission * transmission * INV_PI * le * (G * area);
    }
    phat = luminance(contrib);
    return contrib;
}

// Select one light from the emitted-power distribution in O(1).
public void selectGlobalLight(inout uint proposalSeed, out uint lightIndex) {
    float aliasSample = rndf(proposalSeed) * float(worldPush.lightCount);
    uint column = min(uint(aliasSample), worldPush.lightCount - 1u);
    if (pc.lightAliasAddr != 0) {
        LightAlias a = ConstPtr<LightAlias>(pc.lightAliasAddr)[column];
        bool self = aliasSample - float(column) < a.accept;
        lightIndex = self ? column : a.aliasIndex;
    } else {
        lightIndex = column;
    }
}

public bool findLightGridCell(float3 p, out LightGridCell cell, out int3 cellCoord) {
    cell.spanOffset = 0u;
    cell.spanCount = 0u;
    cell.invWeightSum = 0.0;
    cellCoord = int3(0, 0, 0);
    if (pc.lightGridCellAddr == 0 || pc.lightGridSpanAddr == 0) return false;
    int3 coord = int3(floor((p - worldPush.lightGridOrigin.xyz) / worldPush.lightGridOrigin.w));
    if (coord.x < 0 || coord.y < 0 || coord.z < 0
            || coord.x >= worldPush.lightGridDims.x
            || coord.y >= worldPush.lightGridDims.y
            || coord.z >= worldPush.lightGridDims.z) return false;
    uint linear = (uint(coord.z) * uint(worldPush.lightGridDims.y) + uint(coord.y))
            * uint(worldPush.lightGridDims.x) + uint(coord.x);
    cell = ConstPtr<LightGridCell>(pc.lightGridCellAddr)[linear];
    cellCoord = coord;
    return cell.spanCount > 0u;
}

public void selectSectionLight(uint firstLight, uint lightCount, inout uint proposalSeed,
                        out uint lightIndex) {
    float aliasSample = rndf(proposalSeed) * float(lightCount);
    uint column = min(uint(aliasSample), lightCount - 1u);
    LightAlias alias = ConstPtr<LightAlias>(pc.lightLocalAliasAddr)[firstLight + column];
    bool self = aliasSample - float(column) < alias.accept;
    uint localIndex = self ? column : alias.aliasIndex;
    lightIndex = firstLight + localIndex;
}

public float unpackUnsignedFloat(uint bits, uint mantissaBits) {
    uint mantissaMask = (1u << mantissaBits) - 1u;
    uint mantissa = bits & mantissaMask;
    uint exponent = (bits >> mantissaBits) & 31u;
    if (exponent == 0u) {
        return float(mantissa) * exp2(float(1 - 15 - int(mantissaBits)));
    }
    return (1.0 + float(mantissa) / float(1u << mantissaBits))
            * exp2(float(int(exponent) - 15));
}

public float3 lightRadiance(Light light) {
    uint packed = light.le;
    return float3(unpackUnsignedFloat(packed & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 11u) & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 22u) & 0x3ffu, 5u));
}

public int3 lightSectionCoord(Light light) {
    uint packed = light.section;
    return int3(int(packed & 0x3ffu), int((packed >> 10u) & 0x3ffu),
            int((packed >> 20u) & 0x3ffu));
}

// The half axes share three lanes two-at-a-time; see the Light record in world_common.
public float3 lightHalfU(Light light) {
    return float3(unpackHalf2(light.halfUxy), unpackHalf2(light.halfUzVx).x);
}

public float3 lightHalfV(Light light) {
    return float3(unpackHalf2(light.halfUzVx).y, unpackHalf2(light.halfVyz));
}

// U x V drives both the emitter normal and the rectangle area, so it is computed once and shared.
public float3 lightCrossUV(Light light) {
    return cross(lightHalfU(light), lightHalfV(light));
}

// The rect spans 2U x 2V, so its area is 4|U x V|.
public float lightArea(Light light) {
    return 4.0 * length(lightCrossUV(light));
}

public float3 lightGeometricNormal(Light light) {
    float3 normal = normalize(lightCrossUV(light));
    return (light.section & 0x40000000u) != 0u ? -normal : normal;
}

public float proposalPdf(Light light, float3 le, LightGridCell cell, int3 cellCoord,
                  float localProbability) {
    float power = max(0.0, lightArea(light) * luminance(le));
    float globalPdf = pc.lightAliasAddr != 0
            ? power * worldPush.lightRebase.w : 1.0 / float(worldPush.lightCount);
    float localPdf = 0.0;
    if (localProbability > 0.0 && power > 0.0) {
        int3 delta = lightSectionCoord(light) - cellCoord;
        if (all(abs(delta) <= int3(2, 2, 2))) {
            float3 deltaF = float3(delta);
            localPdf = power * cell.invWeightSum / max(1.0, dot(deltaF, deltaF));
        }
    }
    return localProbability * localPdf + (1.0 - localProbability) * globalPdf;
}

public void selectLightGridSpanLight(LightGridCell cell, inout uint proposalSeed,
                              out uint lightIndex) {
    ConstPtr<LightGridSpan> spans = ConstPtr<LightGridSpan>(pc.lightGridSpanAddr);
    float aliasSample = rndf(proposalSeed) * float(cell.spanCount);
    uint column = min(uint(aliasSample), cell.spanCount - 1u);
    LightGridSpan span = spans[cell.spanOffset + column];
    bool self = aliasSample - float(column) < span.accept;
    uint firstLight = self ? span.firstLight : span.aliasFirstLight;
    uint lightCount = self ? (span.packedLightCounts & 0xffffu)
            : (span.packedLightCounts >> 16u);
    selectSectionLight(firstLight, lightCount, proposalSeed, lightIndex);
}

// Sample the exact mixture q = alpha*qCell + (1-alpha)*qGlobal. qCell first selects a nearby section
// by emitted power and section distance, then its section-local power alias. Every light in
// the neighborhood is represented without a fixed cap; qGlobal gives distant lights full support.
// Alias spans make weighted local selection O(1). The mixture PDF is reconstructed after the single
// Light48 load: section power cancels with the section-local light PDF.
public void selectLightGridLight(LightGridCell cell, bool useLocal, inout uint proposalSeed,
                          out uint lightIndex) {
    if (useLocal) {
        selectLightGridSpanLight(cell, proposalSeed, lightIndex);
    } else {
        selectGlobalLight(proposalSeed, lightIndex);
    }
}

// ---- RIS cost profile. Temporary probes pinned the candidate walk out of the shader to bound what
// memory-side RIS work could ever be worth. Against an
// 18.0ms frame at M=8, removing every dependent load (span -> alias -> record) and all lane divergence
// saved 5.9ms — a third of the frame. Split by vertex that was ~4.3ms at secondary vertices against
// ~1.1ms at the primary hit, and forcing coherent selection recovered at most 1.3ms of it. So the cost
// was chasing depth, not divergence, and it lived at secondary vertices. A presampled candidate pool
// would attack the same 5.9ms structurally, but see the note on
// proposalSeed in tracePath for why it should share the pool rather than the seed.

// Initial RIS over M power-weighted candidates -> one resampled sample, no shadow ray yet. The chosen
// sample's W = wSum / (M * p-hat); M counts every candidate, including zero-weight ones.
public Reservoir risInitial(float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb, float3 F0,
                     float rough, float transmission, inout uint seed, inout uint proposalSeed) {
    Reservoir r = resEmpty();
    bool hasTerrainLights = worldPush.risCandidates > 0u
            && pc.lightBufAddr != 0 && worldPush.lightCount > 0u;
    bool hasProviderLights = worldPush.providerLightAddr != 0 && worldPush.providerLightCount > 0u;
    LightGridCell gridCell;
    int3 gridCellCoord;
    float3 gridLookup = hitPos;
    if (pc.lightGridCellAddr != 0) {
        // Stochastically blend across hard section boundaries. Every cell proposal retains full global
        // support, so conditioning on this independently jittered lookup remains unbiased.
        gridLookup += (float3(rndf(proposalSeed), rndf(proposalSeed), rndf(proposalSeed)) - 0.5)
                * worldPush.lightGridOrigin.w;
    }
    bool hasGridCell = hasTerrainLights && findLightGridCell(gridLookup, gridCell, gridCellCoord);
    uint candidateCount = max(worldPush.risCandidates, hasProviderLights ? 1u : 0u);
    r.M = float(candidateCount);
    // Keep both populations in every candidate's proposal support. Provider lights get one quarter of
    // the mixture when terrain emitters also exist, and the full proposal when they are the only source.
    float providerProbability = hasProviderLights ? (hasTerrainLights ? 0.25 : 1.0) : 0.0;
    uint globalCandidateCount = hasGridCell
            ? max(1u, (candidateCount + 2u) / 4u) : candidateCount;
    float stratifiedLocalProbability = 1.0
            - float(globalCandidateCount) / float(candidateCount);
    float localProbability = hasProviderLights
            ? (hasGridCell ? 0.75 : 0.0) : stratifiedLocalProbability;
    for (uint c = 0u; c < candidateCount; c++) {
        bool providerCandidate = hasProviderLights
                && (!hasTerrainLights || rndf(proposalSeed) < providerProbability);
        if (providerCandidate) {
            uint providerIndex = min(uint(rndf(proposalSeed)
                    * float(worldPush.providerLightCount)), worldPush.providerLightCount - 1u);
            ProviderLight light = ConstPtr<ProviderLight>(worldPush.providerLightAddr)[providerIndex];
            float3 samplePos = light.pos;
            float3 sampleNormal = light.axis0;
            float sampleArea = light.range;
            if (light.type == 0u) {
                float s = rndf(seed) * 2.0 - 1.0;
                float t = rndf(seed) * 2.0 - 1.0;
                samplePos += s * light.axis0 + t * light.axis1;
                sampleNormal = normalize(cross(light.axis0, light.axis1)) * light.shapeScalar;
                sampleArea = 4.0 * length(cross(light.axis0, light.axis1));
            } else if (light.type == 3u) {
                sampleNormal = sampleUniformCone(light.axis0, light.shapeScalar, seed);
            }
            float phat;
            if (light.type == 0u) {
                evalSampleContrib(samplePos, sampleNormal, light.intensity, sampleArea,
                        hitPos, n, v, rd, diffAlb, F0, rough, transmission, phat);
            } else if (light.type == 3u) {
                evalDistantLightContrib(sampleNormal, light.intensity, light.shapeScalar,
                        n, v, diffAlb, F0, rough, transmission, phat);
            } else {
                evalDeltaLightContrib(samplePos, light.axis0, light.intensity, light.range,
                        light.shapeScalar, light.metersPerWorldUnit, light.type, hitPos, n, v, diffAlb, F0,
                        rough, transmission, phat);
            }
            if (phat <= 0.0) continue;
            // This is the discrete population PDF. Rectangle area and the normalized distant angular
            // proposal are already canceled inside their contribution evaluations; point and spot are
            // delta distributions and have no continuous shape PDF.
            float sourcePdf = providerProbability / float(worldPush.providerLightCount);
            float w = phat / max(sourcePdf, 1.0e-20);
            r.wSum += w;
            if (rndf(seed) * r.wSum < w) {
                r.pos = samplePos;
                r.lnrm = sampleNormal;
                r.le = light.intensity;
                r.area = sampleArea;
                r.phat = phat;
                r.lightType = light.type;
                r.shapeScalar = light.shapeScalar;
                r.metersPerWorldUnit = light.metersPerWorldUnit;
            }
            continue;
        }
        uint li;
        uint globalsBefore = (c * globalCandidateCount) / candidateCount;
        uint globalsAfter = ((c + 1u) * globalCandidateCount) / candidateCount;
        // With provider lights every candidate samples the exact full mixture independently. The
        // terrain-only path keeps its lower-variance deterministic 3:1 cell/global stratification.
        bool useLocal = hasGridCell && (hasProviderLights
                ? rndf(proposalSeed) < localProbability : globalsAfter == globalsBefore);
        selectLightGridLight(gridCell, useLocal, proposalSeed, li);
        Light lg = ConstPtr<Light>(pc.lightBufAddr)[li];
        // Soft shadows: uniform point on the emitter rectangle ((s,t) in [-1,1]^2, pdf 1/area).
        float s = rndf(seed) * 2.0 - 1.0;
        float t = rndf(seed) * 2.0 - 1.0;
        float3 sp = lg.pos + worldPush.lightRebase.xyz
                + s * lightHalfU(lg) + t * lightHalfV(lg);
        float3 le = lightRadiance(lg);
        float3 lightNormal = lightGeometricNormal(lg);
        float area = lightArea(lg);
        float phat;
        evalSampleContrib(sp, lightNormal, le, area, hitPos, n, v, rd,
                diffAlb, F0, rough, transmission, phat);
        if (phat <= 0.0) {
            continue;
        }
        float sourcePdf = (1.0 - providerProbability)
                * proposalPdf(lg, le, gridCell, gridCellCoord, localProbability);
        float w = phat / max(sourcePdf, 1.0e-20);
        r.wSum += w;
        if (rndf(seed) * r.wSum < w) { // weighted reservoir update
            r.pos = sp;
            r.lnrm = lightNormal;
            r.le = le;
            r.area = area;
            r.phat = phat;
            r.lightType = 0u;
            r.shapeScalar = -1.0;
            r.metersPerWorldUnit = 1.0;
        }
    }
    r.W = r.phat > 0.0 ? (r.wSum / (r.M * r.phat)) : 0.0;
    return r;
}

// Shade a finalized reservoir: recompute the survivor's contribution at (hitPos,n,v), cast ONE shadow
// ray, and return throughput-free radiance contrib*vis*W. The one ray serves whichever term fired for
// the survivor, reflection or transmission — the origin is biased toward the sample's side of the
// surface.
public float3 shadeReservoir<TSurfaces : ISurfaceDispatch>(TSurfaces surfaces, Reservoir s,
        float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb,
        float3 F0, float rough, float transmission, Medium currentMedium) {
    if (s.W <= 0.0 || s.phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float phat;
    float3 contrib;
    if (s.lightType == 0u) {
        contrib = evalSampleContrib(s.pos, s.lnrm, s.le, s.area, hitPos, n, v, rd,
                diffAlb, F0, rough, transmission, phat);
    } else if (s.lightType == 3u) {
        contrib = evalDistantLightContrib(s.lnrm, s.le, s.shapeScalar,
                n, v, diffAlb, F0, rough, transmission, phat);
    } else {
        contrib = evalDeltaLightContrib(s.pos, s.lnrm, s.le, s.area, s.shapeScalar,
                s.metersPerWorldUnit, s.lightType, hitPos, n, v, diffAlb, F0, rough,
                transmission, phat);
    }
    if (phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    if (s.lightType == 3u) {
        float side = dot(n, s.lnrm) >= 0.0 ? 1.0 : -1.0;
        float3 origin = hitPos + side * n * SURF_BIAS;
        bool hasExtinction = any(currentMedium.extinction > 0.0);
        bool boundaryLighting = (currentMedium.flags & MEDIUM_BOUNDARY_LIGHTING) != 0u;
        VisibilityResult shadow = visibility(origin, s.lnrm, RAY_TMAX,
                hasExtinction || boundaryLighting, currentMedium.implementation);
        float3 vis = shadow.transmittance;
        if (hasExtinction) {
            // A distant emitter whose ray never leaves the current medium has an infinite optical path.
            vis *= shadow.boundaryHitT > 0.0
                    ? exp(-currentMedium.extinction * shadow.boundaryHitT) : float3(0.0);
        }
        if (boundaryLighting && shadow.boundaryHitT > 0.0) {
            MediumLightingInput input;
            input.boundaryPosition = origin + s.lnrm * shadow.boundaryHitT;
            input.proceduralPosition = input.boundaryPosition + worldPush.proceduralDomainOffset;
            input.lightDirection = s.lnrm;
            input.boundaryDistance = shadow.boundaryHitT;
            input.mediumIor = currentMedium.ior;
            input.time = worldPush.time;
            input.surfaceFlags = (worldPush.flags & 16u) != 0u
                    ? SURFACE_ANIMATION_ENABLED : 0u;
            vis *= surfaces.evaluateMediumLighting(currentMedium.implementation, input);
        }
        return contrib * vis * s.W;
    }
    // Pick the shadow-ray origin on the sample's side of the surface FIRST, then measure the ray from
    // that origin — measuring dist from hitPos while tracing from the biased origin shifts the ray tip
    // up to SURF_BIAS*ndl toward the emitter plane, beating the 0.001*dist stop-short margin for lights
    // closer than ~SURF_BIAS/0.001 blocks -> shadow ray hits the emitter's own face -> per-texel black.
    float side = dot(n, s.pos - hitPos) >= 0.0 ? 1.0 : -1.0;
    float3 origin = hitPos + side * n * SURF_BIAS;
    float3 toL = s.pos - origin;
    float dist = length(toL);
    // Stop just short of the sample point so the ray doesn't self-occlude on the emitter's own face.
    bool hasExtinction = any(currentMedium.extinction > 0.0);
    bool boundaryLighting = (currentMedium.flags & MEDIUM_BOUNDARY_LIGHTING) != 0u;
    VisibilityResult shadow = visibility(origin, toL / dist, dist * 0.999,
            hasExtinction || boundaryLighting, currentMedium.implementation);
    float3 vis = shadow.transmittance;
    if (hasExtinction) {
        float mediumDistance = shadow.boundaryHitT > 0.0 ? shadow.boundaryHitT : dist;
        vis *= exp(-currentMedium.extinction * mediumDistance);
    }
    if (boundaryLighting && shadow.boundaryHitT > 0.0) {
        MediumLightingInput input;
        input.boundaryPosition = origin + (toL / dist) * shadow.boundaryHitT;
        input.proceduralPosition = input.boundaryPosition + worldPush.proceduralDomainOffset;
        input.lightDirection = toL / dist;
        input.boundaryDistance = shadow.boundaryHitT;
        input.mediumIor = currentMedium.ior;
        input.time = worldPush.time;
        input.surfaceFlags = (worldPush.flags & 16u) != 0u
                ? SURFACE_ANIMATION_ENABLED : 0u;
        vis *= surfaces.evaluateMediumLighting(currentMedium.implementation, input);
    }
    return contrib * vis * s.W;
}

// TLAS instance-mask bits (set on the Java side, ANDed against these per-ray cull masks):
//   bit 0 (0x01) — secondary rays: shadows, GI bounces, reflections.
//   bit 1 (0x02) — the primary camera ray.
// Terrain / entities / block entities keep 0xFF (both bits). Particles use 0x02: primary-visible
