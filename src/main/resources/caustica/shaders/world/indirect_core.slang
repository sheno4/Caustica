// Shared composition-generic indirect integrator. The engine owns path bookkeeping, termination, medium state,
// dielectric transitions, ray cones, particle shading, and direct-light gating. Opaque vertices call the
// selected surface slot for BSDF evaluation, sampling, and the post-estimator response.
//
// The closure is reconstructed from the payload written by closest_hit.slang, so no material policy is
// re-evaluated here. Celestial visibility is derived from BsdfSample.eventFlags. The built-in model uses
// a tight continuous GGX lobe at zero roughness because the slot contract has no delta-lobe event.
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
import bindings;
import caustica_types;
import caustica_surface;
import caustica_api;
import frame;
import trace_policy;
// Anchors pass-declared resource modules as plain imports for indirect.slang and indirect_ser.slang, the
// two entry points built on this integrator. Required even though nothing here reads one: see the same
// import in closest_hit.slang, and sky_miss.slang for the compiler rule behind it.
import caustica_pass_resources;

// Rebuilds the closure closest_hit.slang's createSurface already computed for this hit. Its
// fields are exactly what the payload carries (diffuseAlbedo/specularF0/alphaRoughness/subsurface/
// transmission), so this is a struct literal, not a re-evaluation of the material.
SurfaceClosure payloadSurfaceClosure(float3 n) {
    SurfaceClosure closure;
    closure.shadingNormal = n;
    closure.diffuseAlbedo = payloadAlbedo();
    closure.specularF0 = payload.f0;
    closure.alphaRoughness = clamp(payloadRoughness(), 0.0, 1.0);
    closure.emissionRadiance = float3(0.0); // not read by evaluateBsdf/sampleBsdf; see payload doc
    closure.transmission = payloadTransmission();
    closure.interiorIor = max(payloadIor(), 1.0);
    closure.subsurface = payloadSss();
    closure.flags = 0u;
    return closure;
}

public float3 tracePath<TC : IComposition, TTrace : IRadianceTrace>(TC.Surface surfaceModel,
        FrameContext frameCtx, PathSegment seg,
        uint2 pix, uint sampleIndex) {
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
    int indirectDepth = 0;
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
        uint material = payloadMaterial();
        int hitDepth = indirectDepth++;

        // ---- Dielectric interface: engine-owned in full.
        if (material == MATERIAL_WATER || material == MATERIAL_DIELECTRIC) {
            if (bounce >= maxBounces) {
                break;
            }
            bool isWater = material == MATERIAL_WATER;
            bool entering = payloadDielectricEntering();
            float3 tint = payloadAlbedo();
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);

            float3 geometricNormal = n;
            if (isWater && waterWaves) {
                float2 waterDomain = hitPos.xz + worldPush.waterAnchor.xy;
                float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.2);
                n = applyWaterWaves(geometricNormal, waterDomain,
                                    worldPush.waterParams.w, waterFootprint);
            }

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
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, SURF_BIAS);
            } else {
                if (dot(transmittedDir, transmittedDir) <= 0.0) break;
                rd = normalize(transmittedDir);
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, transmitBias);
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

        // ---- Particles: fixed camera-facing receiver, not routed through the surface slot.
        if (material == MATERIAL_PARTICLE) {
            float3 albedo = payloadAlbedo();

            float3 lightDir = celestialLight.dir;
            float lightHalfAngle = celestialLight.halfAngle;
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
            }
            float signedNdl = dot(n, lightDir);
            float ndl = abs(signedNdl);
            if (ndl > 0.0) {
                float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                float3 vis = visibility(shadowOrigin, lightDir, 10000.0).transmittance;
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    L += throughput * albedo * INV_PI * celestialLight.illuminance * ndl * vis;
                }
            }

            if (risOn) {
                float3 v = -rd;
                Reservoir r = risInitial(hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                         true, 0.0, seed, proposalSeed);
                L += throughput * shadeReservoir(r, hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                                 true, 0.0);
            }

            if (bounce >= maxBounces) {
                break;
            }
            throughput *= albedo;
            ro = hitPos + n * SURF_BIAS;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false;
            continue;
        }

        // ---- Opaque terrain/entity: the reflective closure built at closest-hit.
        float3 p = hitPos + n * SURF_BIAS;
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

        // NEE toward the dominant celestial body: the slot BSDF is the estimator's target function.
        float3 lightDir = celestialLight.dir;
        float lightHalfAngle = celestialLight.halfAngle;
        if (lightHalfAngle > 0.0) {
            lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
        }
        float ndl = max(0.0, dot(n, lightDir));
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
                BsdfEvaluation eval = surfaceModel.evaluateBsdf(bsdfQuery);
                vertexRadiance += eval.value * celestialLight.illuminance * ndl * vis;
            }
        }

        // RIS direct lighting from block emitters: the engine's own reference target function (matching
        // evalSampleContrib in lighting.slang), evaluated against the same diffAlb/F0/rough the closure
        // carries. RIS still uses the engine reference target function rather than evaluateBsdf.
        if (risOn) {
            float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? closure.subsurface : 0.0;
            Reservoir r = risInitial(hitPos, n, v, rd, diffAlb, F0, rough, false, activeSss,
                                     seed, proposalSeed);
            vertexRadiance += shadeReservoir(r, hitPos, n, v, rd, diffAlb, F0, rough, false,
                                             activeSss);
        }

        // Thin-surface SSS transmission is a fixed engine-owned HG closed form.
        if (closure.subsurface > 0.0 && hitDepth <= MAX_SSS_INDIRECT_DEPTH) {
            float backNdl = max(0.0, dot(-n, lightDir));
            if (backNdl > 0.0) {
                VisibilityResult shadowBack = visibility(hitPos - n * SURF_BIAS, lightDir, 10000.0);
                float3 visB = shadowBack.transmittance;
                if (medium.current.water && waterWaves && shadowBack.waterHitT > 0.0) {
                    visB *= waterCaustic(hitPos + lightDir * shadowBack.waterHitT,
                                         lightDir, shadowBack.waterHitT);
                }
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    float cosT = dot(lightDir, rd);
                    vertexRadiance += diffAlb * closure.subsurface * SSS_STRENGTH * hg(cosT, SSS_G)
                            * backNdl * celestialLight.illuminance * visB;
                }
            }
        }

        // Look policy applies once to this vertex's total shaded contribution; it is identity
        // for the built-in surface, so this is exactly the sum of the terms above.
        L += throughput * surfaceModel.evaluateResponse(vertexRadiance, closure);

        if (bounce >= maxBounces) {
            break;
        }

        // Indirect continuation: the slot samples its BSDF. throughputWeight already carries
        // value*|N.L|/pdf (the estimator contract), so no separate lobe-selection division is needed
        // here; no separate lobe-selection division is applied.
        BsdfSampleQuery sampleQuery;
        sampleQuery.surface = closure;
        sampleQuery.outgoingDirection = v;
        sampleQuery.random = float3(rndf(seed), rndf(seed), rndf(seed));
        BsdfSample sample = surfaceModel.sampleBsdf(sampleQuery);
        if (sample.pdf <= 0.0) {
            break;
        }
        throughput *= sample.throughputWeight;
        ro = p;
        rd = sample.incomingDirection;
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
    // celestialLight (world_core.slang) stays at its zero-illuminance default: the sky's atmosphere LUT
    // The engine has no sun or moon as a light: celestialLight sits at its zero-illuminance default.
    // All three consumers below (particle lighting, main NEE, SSS backscatter) gate on
    // ndl/illuminance, so a zero light fires no shadow ray. A sky extension submitting sun and moon
    // through LightProvider.submitLights, consumed by the engine, is what makes these real.
    FrameContext frameCtx = frameContext(worldPush);
    TC.Surface surfaceModel;
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
            frameRadiance += tracePath<TC, TTrace>(surfaceModel, frameCtx, segment,
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
