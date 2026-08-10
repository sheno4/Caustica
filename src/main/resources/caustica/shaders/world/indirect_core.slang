// Shared composition-generic indirect integrator. The engine owns path bookkeeping, termination, medium
// state, dielectric transitions, ray cones, direct-light gating, and the BSDF itself; the surface
// implementation a material named is called only for the post-estimator response.
//
// The closure is reconstructed from the payload written by closest_hit.slang, so no material policy is
// re-evaluated here. Celestial visibility is derived from BsdfSample.eventFlags. The BSDF uses a tight
// continuous GGX lobe at zero roughness because the estimator has no delta-lobe event.
//
// Thin entry modules select ordinary tracing or EXT_SER while this integrator stays shared.
module indirect_core;

import world_common;
import world_core;
import math;
import medium;
import segment;
import water;
import trace;
import lighting;
import surface_bsdf;
import bindings;
import caustica_types;
import caustica_surface;
import caustica_api;
import trace_policy;
// Anchors pass-declared resource modules as plain imports for indirect.slang and indirect_ser.slang, the
// two entry points built on this integrator. Required even though nothing here reads one: see the same
// import in closest_hit.slang, and sky_miss.slang for the compiler rule behind it.
import caustica_pass_resources;

public float3 tracePath<TC : IComposition, TTrace : IRadianceTrace>(TC.Surfaces surfaces,
        PathSegment seg, uint2 pix, uint sampleIndex) {
    float3 L = float3(0.0, 0.0, 0.0);
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;
    uint seed = seg.seed ^ sampleIndex * 2246822519u;
    seed = pcg(seed);
    float rayConeWidth = seg.rayConeWidth;
    float rayConeSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(worldPush.maxBounces);
    int rrStart = maxBounces <= 3 ? 1 : 2;
    bool risOn = worldPush.risCandidates > 0u && worldPush.lightCount > 0u && pc.lightBufAddr != 0;
    uint proposalSeed = pix.x * 1973u + pix.y * 9277u + 26699u
            ^ worldPush.frameIndex * 2654435761u ^ sampleIndex * 2246822519u;
    proposalSeed = pcg(proposalSeed);
    MediumStack medium = seg.medium;
    bool waterWaves = (worldPush.flags & 16u) != 0u;
    bool showCelestial = seg.showCelestial;
    TTrace tracer;
    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
        uint pathPhaseHint = bounce >= maxBounces ? 2u : (bounce >= rrStart ? 1u : 0u);
        tracer.trace(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                showCelestial, rayConeWidth, rayConeSpread, pathPhaseHint);

        if (payload.hitT < 0.0) {
            if (medium.current.water) {
                break;
            }
            float3 sky = payloadSky();
            L += throughput * sky;
            break;
        }

        if (any(medium.current.extinction > 0.0)) {
            throughput *= exp(-medium.current.extinction * payload.hitT);
        }

        float3 n = payloadNormal();
        float3 hitPos = ro + rd * payload.hitT;
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);

        // ---- Dielectric interface: engine-owned in full. The normal is whatever closest-hit published,
        // waves and all, so nothing is re-derived here.
        if (payloadDielectric()) {
            if (bounce >= maxBounces) {
                break;
            }
            bool isWater = payloadWater();
            bool entering = payloadDielectricEntering();
            float3 tint = payloadAlbedo();
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);

            Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0), tint, transmission);
            float etaI = medium.current.ior;
            float etaT = entering ? entered.ior : medium.outer.ior;

            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            float F = fresnelDielectric(cosI, etaI, etaT);
            float3 transmittedDir = refract(rd, n, etaI / etaT);
            float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;

            bool chooseReflection = rndf(seed) < F;
            if (chooseReflection) {
                rd = reflect(rd, n);
                ro = offsetSurfaceOrigin(hitPos, n, rd, SURF_BIAS);
            } else {
                if (dot(transmittedDir, transmittedDir) <= 0.0) break;
                rd = normalize(transmittedDir);
                ro = offsetSurfaceOrigin(hitPos, n, rd, transmitBias);
                if (entering) {
                    mediumPush(medium, entered);
                } else {
                    mediumPop(medium);
                }
            }
            showCelestial = true;
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(seed) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        // ---- Every surface that does not bound a medium: the closure built at closest-hit. Terrain,
        // entities and billboards differ only in the parameters they carry.
        float3 v = -rd;
        SurfaceClosure closure = payloadSurfaceClosure(n);
        float3 diffAlb = closure.diffuseAlbedo;
        float3 F0 = closure.specularF0;
        float rough = closure.alphaRoughness;

        float3 vertexRadiance = float3(0.0, 0.0, 0.0);

        // Emissive surfaces: unchanged engine bookkeeping (the payload has no independent emission
        // colour channel yet; see closest_hit.slang's material input construction).
        float emission = payloadEmission();
        bool gateEmitter = risOn && payloadEmitterInList();
        if (emission > 0.0 && (!gateEmitter || showCelestial)) {
            vertexRadiance += payloadAlbedo() * emission;
        }

        // NEE toward the dominant celestial body: the engine BSDF is the estimator's target function. One
        // ray serves both hemispheres — a light is either in front of the surface, where the BSDF answers
        // with reflection, or behind it, where a thin wall answers with transmission and an opaque one
        // answers with nothing. The origin is biased onto whichever side the light is.
        float3 lightDir = celestialLight.dir;
        float lightHalfAngle = celestialLight.halfAngle;
        if (lightHalfAngle > 0.0) {
            lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
        }
        float signedNdl = dot(n, lightDir);
        float ndl = abs(signedNdl);
        float3 p = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
        if (ndl > 0.0) {
            VisibilityResult shadow = visibility(p, lightDir, 10000.0);
            float3 vis = shadow.transmittance;
            if (medium.current.water && waterWaves && shadow.waterHitT > 0.0) {
                vis *= waterCaustic(p + lightDir * shadow.waterHitT, lightDir, shadow.waterHitT);
            }
            if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                BsdfQuery bsdfQuery;
                bsdfQuery.surface = closure;
                bsdfQuery.outgoingDirection = v;
                bsdfQuery.incomingDirection = lightDir;
                BsdfEvaluation eval = evaluateBsdf(bsdfQuery);
                vertexRadiance += eval.value * celestialLight.illuminance * ndl * vis;
            }
        }

        // RIS direct lighting from block emitters: the engine's own reference target function (matching
        // evalSampleContrib in lighting.slang), evaluated against the same diffAlb/F0/rough the closure
        // carries. RIS still uses the engine reference target function rather than evaluateBsdf.
        if (risOn) {
            Reservoir r = risInitial(hitPos, n, v, rd, diffAlb, F0, rough, closure.transmission,
                                     seed, proposalSeed);
            vertexRadiance += shadeReservoir(r, hitPos, n, v, rd, diffAlb, F0, rough,
                                             closure.transmission);
        }

        // Look policy applies once to this vertex's total shaded contribution, through the same
        // implementation the material named at closest-hit; it is identity for the built-in surface, so
        // this is exactly the sum of the terms above.
        L += throughput * surfaces.evaluateResponse(payloadSurfaceImpl(), vertexRadiance, closure);

        if (bounce >= maxBounces) {
            break;
        }

        // Indirect continuation. throughputWeight already carries value*|N.L|/pdf (the estimator
        // contract), so no separate lobe-selection division is applied here.
        BsdfSampleQuery sampleQuery;
        sampleQuery.surface = closure;
        sampleQuery.outgoingDirection = v;
        sampleQuery.random = float3(rndf(seed), rndf(seed), rndf(seed));
        BsdfSample sample = sampleBsdf(sampleQuery);
        if (sample.pdf <= 0.0) {
            break;
        }
        throughput *= sample.throughputWeight;
        rd = sample.incomingDirection;
        // A transmitted bounce leaves through the far side, so the origin follows the direction rather
        // than the normal.
        ro = hitPos + (dot(n, rd) >= 0.0 ? n : -n) * SURF_BIAS;
        // Engine invariant derived from the slot's lobe classification: a diffuse
        // bounce is already covered by the NEE term above, so it must not also see the celestial disc.
        showCelestial = (sample.eventFlags & EVENT_DIFFUSE) == 0u;
        rayConeSpread = max(rayConeSpread,
                showCelestial ? rough * RAY_CONE_GLOSSY_SPREAD_SCALE : RAY_CONE_DIFFUSE_SPREAD);

        if (bounce >= rrStart) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(seed) > q) {
                break;
            }
            throughput /= q;
        }
    }
    return L;
}

public void runIndirect<TC : IComposition, TTrace : IRadianceTrace>() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    // celestialLight (world_core.slang) is a fixed hand-authored sun, so the three consumers below
    // NEE fires against the same constant direction and illuminance every frame, regardless of the time
    // of day the sky slot is drawing.
    TC.Surfaces surfaces;
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    int2 pix = int2(dispatchIndex);
    uint spp = max(worldPush.spp, 1u);
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    ConstPtr<PackedPathSegment> queue = ConstPtr<PackedPathSegment>(pc.pathQueueAddr);
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    uint recordIndex = pixelIndex;
    for (uint leaf = 0u; leaf < MAX_PATH_SEGMENTS; ++leaf) {
        PackedPathSegment packed = queue[recordIndex];
        PathSegment segment = unpackPathSegment(packed);
        for (uint s = 0u; s < spp; ++s) {
            frameRadiance += tracePath<TC, TTrace>(surfaces, segment,
                    uint2(pix), s + leaf * spp);
        }
        if (packed.nextRecord == PATH_NO_NEXT) {
            break;
        }
        recordIndex = packed.nextRecord;
    }
    float3 stored = frameRadiance * (worldPush.preExposure / float(spp));
    outImage[pix] = float4(clamp(stored, float3(0.0), float3(HALF_MAX)), 1.0);
}
