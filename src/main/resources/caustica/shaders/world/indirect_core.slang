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
import trace;
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
    MediumStack medium = seg.medium;
    bool showEnvironmentEmitter = seg.showEnvironmentEmitter;
    TTrace tracer;
    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
        uint pathPhaseHint = bounce >= maxBounces ? 2u : (bounce >= rrStart ? 1u : 0u);
        tracer.trace(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, RAY_TMAX,
                showEnvironmentEmitter, rayConeWidth, rayConeSpread, pathPhaseHint);

        if (payload.hitT < 0.0) {
            if ((medium.current.flags & MEDIUM_TERMINATE_ON_MISS) != 0u) {
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
        // including procedural perturbations, so nothing is re-derived here.
        if (payloadDielectric()) {
            if (bounce >= maxBounces) {
                break;
            }
            bool entering = payloadDielectricEntering();
            float3 tint = payloadAlbedo();
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);

            MediumBoundary boundary = evaluateMedium(surfaces, payloadSurfaceImpl(),
                    max(payloadIor(), 1.0), tint, transmission);
            Medium entered = boundary.medium;
            float etaI = medium.current.ior;
            float etaT = entering ? entered.ior : medium.outer.ior;

            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            float F = fresnelDielectric(cosI, etaI, etaT);
            float3 transmittedDir = refract(rd, n, etaI / etaT);
            float transmitBias = boundary.transmissionRayBias;

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
            showEnvironmentEmitter = true;
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(seed) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        // ---- Every surface that does not bound a medium: the closure built at closest-hit. Geometry
        // sources differ only in the parameters they carry.
        float3 v = -rd;
        SurfaceClosure closure = payloadSurfaceClosure(n);
        float rough = closure.alphaRoughness;

        float3 vertexRadiance = float3(0.0, 0.0, 0.0);

        // Emissive surfaces carry the resolved OpenPBR emission colour independently from base colour.
        float3 emissionRadiance = closure.emissionRadiance;
        if (any(emissionRadiance > 0.0)) {
            vertexRadiance += emissionRadiance;
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
        // With no direct-light estimator, environment emitters remain visible to every continuation.
        bool diffuseEvent = (sample.eventFlags & EVENT_DIFFUSE) != 0u;
        showEnvironmentEmitter = true;
        rayConeSpread = max(rayConeSpread,
                diffuseEvent ? RAY_CONE_DIFFUSE_SPREAD : rough * RAY_CONE_GLOSSY_SPREAD_SCALE);

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
