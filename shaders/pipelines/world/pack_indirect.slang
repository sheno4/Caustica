// Engine indirect raygen specialized with the selected ray pack's surface model
// (docs/EXTENSION_API.md section 4.1/4.2). Everything that is engine invariant per the
// loop-contract table in section 4.2 — L/throughput/pdf bookkeeping, Russian roulette, the medium stack,
// ray-cone propagation, the dielectric interface (Fresnel/reflect/refract/medium push-pop), particle
// billboard shading, and RIS emitter-NEE's own target function — stays exactly as indirect.rgen.slang's
// (that file is the build-time fallback and is left untouched).
//
// The only thing this file changes is the OPAQUE terrain/entity vertex: instead of an inlined
// Lambert+Cook-Torrance BSDF, it reconstructs the PackSurfaceClosure that pack_closest_hit.slang's
// TPack.createSurface already computed (its fields are exactly what the payload already carries — no
// extra ABI, no recomputation) and calls TPack.evaluateBsdf for the celestial NEE term, TPack.sampleBsdf
// for the continuation, and TPack.evaluateResponse once over the vertex's total shaded contribution
// (section 4.1: "applied to an already-shaded, already-estimated contribution").
//
// Celestial-disc visibility for the continuation ray is now genuinely DERIVED from
// PackBsdfSample.eventFlags (section 4.2's "not yet implemented" engine invariant) instead of hardcoded
// to which local branch fired.
//
// Known divergence while this path is enabled: the default pack's BSDF has no delta/mirror lobe — it
// floors alphaRoughness at 4e-4 (caustica_default_surface.defaultCreateSurface) and always takes a
// continuous GGX sample, where indirect.rgen.slang's isDeltaAlpha fast path takes an exact mirror
// reflection below that same threshold. A roughness-0 (mirror) material therefore renders as an
// extremely tight but finite glossy lobe rather than an exact delta, because PACK_EVENT_* has no distinct
// delta-lobe flag to ask for one. Not a bug: the Pack API 0.1 contract does not yet expose that
// distinction (see docs/EXTENSION_API.md section 4.1).
//
// Deliberately named WITHOUT a `.rgen.` stage infix (same convention as pack_sky_miss.slang): it is
// generic over TPack, so a build-time compile would fail for want of a type argument. Reached only
// through the runtime pack compiler. The EXT_SER-reordered variant has no equivalent here (see
// RayPackShaderCompiler's class doc); this always uses the ordinary TraceRay path.
import world_common;
import world_core;
import math;
import medium;
import segment;
import water;
import trace;
import lighting;
import sky;
import bindings;
import caustica_ray_pack_api;
import pack_frame;

// Rebuilds the closure pack_closest_hit.slang's TPack.createSurface already computed for this hit. Its
// fields are exactly what the payload carries (diffuseAlbedo/specularF0/alphaRoughness/subsurface/
// transmission), so this is a struct literal, not a re-evaluation of the material.
PackSurfaceClosure payloadSurfaceClosure(float3 n) {
    PackSurfaceClosure closure;
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

public float3 tracePath<TPack : IRayPack>(TPack pack, PackFrameContext frameCtx, PathSegment seg,
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
    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
        traceRadiance(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                      showCelestial, rayConeWidth, rayConeSpread);

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

        // ---- Dielectric interface: engine-owned in full, unchanged from indirect.rgen.slang.
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

        // ---- Particles: fixed camera-facing receiver, not routed through the pack (see file doc).
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

        // ---- Opaque terrain/entity: the reflective closure the pack already built at closest-hit.
        float3 p = hitPos + n * SURF_BIAS;
        float3 v = -rd;
        PackSurfaceClosure closure = payloadSurfaceClosure(n);
        float3 diffAlb = closure.diffuseAlbedo;
        float3 F0 = closure.specularF0;
        float rough = closure.alphaRoughness;

        float3 vertexRadiance = float3(0.0, 0.0, 0.0);

        // Emissive surfaces: unchanged engine bookkeeping (the payload has no independent emission
        // colour channel yet; see pack_closest_hit's buildPackMaterialInput doc).
        float emission = payloadEmission();
        bool gateEmitter = risOn && payloadEmitterInList();
        if (emission > 0.0 && (!gateEmitter || showCelestial)) {
            vertexRadiance += payloadAlbedo() * emission;
        }

        // NEE toward the dominant celestial body: the pack's BSDF is the estimator's target function.
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
                PackBsdfQuery bsdfQuery;
                bsdfQuery.surface = closure;
                bsdfQuery.outgoingDirection = v;
                bsdfQuery.incomingDirection = lightDir;
                PackBsdfEvaluation eval = pack.evaluateBsdf(bsdfQuery);
                vertexRadiance += eval.value * celestialLight.illuminance * ndl * vis;
            }
        }

        // RIS direct lighting from block emitters: the engine's own reference target function (matching
        // evalSampleContrib in lighting.slang), evaluated against the same diffAlb/F0/rough the closure
        // carries. Not yet routed through TPack.evaluateBsdf — see docs/EXTENSION_API.md section 11's
        // convergence-debt note; that generalization is separate, larger surface (lighting.slang's RIS
        // target function) than this pass covers.
        if (risOn) {
            float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? closure.subsurface : 0.0;
            Reservoir r = risInitial(hitPos, n, v, rd, diffAlb, F0, rough, false, activeSss,
                                     seed, proposalSeed);
            vertexRadiance += shadeReservoir(r, hitPos, n, v, rd, diffAlb, F0, rough, false,
                                             activeSss);
        }

        // Thin-surface SSS transmission: a fixed HG closed form, not part of the Pack API's BSDF
        // contract (IPackSurfaceModel has no phase-function query), so unchanged from indirect.rgen.slang.
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

        // Look policy applied once to this vertex's total shaded contribution (section 4.1); identity
        // for the default pack, so this is exactly the sum of the terms above for the bundled look.
        L += throughput * pack.evaluateResponse(vertexRadiance, closure);

        if (bounce >= maxBounces) {
            break;
        }

        // Indirect continuation: the pack samples its own BSDF. throughputWeight already carries
        // value*|N.L|/pdf (the estimator contract), so no separate lobe-selection division is needed
        // here — unlike indirect.rgen.slang's hand-rolled mixture, which divided by its own local
        // selection probability `ps`.
        PackBsdfSampleQuery sampleQuery;
        sampleQuery.surface = closure;
        sampleQuery.outgoingDirection = v;
        sampleQuery.random = float3(rndf(seed), rndf(seed), rndf(seed));
        PackBsdfSample sample = pack.sampleBsdf(sampleQuery);
        if (sample.pdf <= 0.0) {
            break;
        }
        throughput *= sample.throughputWeight;
        ro = p;
        rd = sample.incomingDirection;
        // Engine invariant derived from the pack's own lobe classification (section 4.2): a diffuse
        // bounce is already covered by the NEE term above, so it must not also see the celestial disc.
        showCelestial = (sample.eventFlags & PACK_EVENT_DIFFUSE) == 0u;
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

[shader("raygeneration")]
void main<TPack : IRayPack>() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    celestialLight = dominantCelestialLight(skyState(worldPush), transmittanceLut);
    celestialLight.illuminance = bt709ToAcesCg(celestialLight.illuminance);
    PackFrameContext frameCtx = packFrameContext(worldPush, skyState(worldPush));
    TPack pack;
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
            frameRadiance += tracePath<TPack>(pack, frameCtx, segment, uint2(pix), s + leaf * spp);
        }
        if (packed.nextRecord == PATH_NO_NEXT) {
            break;
        }
        recordIndex = packed.nextRecord;
    }
    float3 stored = frameRadiance * (worldPush.preExposure / float(spp));
    outImage[pix] = float4(clamp(stored, float3(0.0), float3(HALF_MAX)), 1.0);
}
