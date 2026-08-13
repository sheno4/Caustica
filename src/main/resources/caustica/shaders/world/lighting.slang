// Direct lighting from one source-neutral retained/transient GPU light scene.
module lighting;

import world_common;
import world_core;
import bindings;
import math;
import medium;
import trace;
import caustica_surface;
import caustica_types;
import surface_bsdf;

public struct Reservoir {
    public float3 pos;
    public float3 lnrm;
    public float3 le;
    public float area;
    public float M;
    public float W;
    public float wSum;
    public float phat;
    public uint lightType;
    public float shapeScalar;
    public float metersPerWorldUnit;
};

public Reservoir resEmpty() {
    Reservoir r;
    r.pos = float3(0.0);
    r.lnrm = float3(0.0);
    r.le = float3(0.0);
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

float3 evaluateClosureResponse(SurfaceClosure surface, float3 outgoingDirection,
        float3 incomingDirection, out float phat) {
    BsdfQuery query;
    query.surface = surface;
    query.outgoingDirection = outgoingDirection;
    query.incomingDirection = incomingDirection;
    BsdfEvaluation evaluation = evaluateBsdf(query);
    float3 response = evaluation.value * abs(dot(surface.shadingNormal, incomingDirection));
    phat = luminance(response);
    return response;
}

// Intensity is candela and squared distance is converted to metres before inverse-square falloff.
public float3 evalDeltaLightContrib(float3 lightPos, float3 lightDirection, float3 intensity,
        float range, float outerCos, float metersPerWorldUnit, uint lightType,
        float3 hitPos, float3 v, SurfaceClosure surface, out float phat) {
    phat = 0.0;
    float3 toL = lightPos - hitPos;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-6 || dist2 > range * range) return float3(0.0);
    float3 wi = toL * rsqrt(dist2);
    if (lightType == 2u && dot(lightDirection, -wi) < outerCos) return float3(0.0);
    float responsePhat;
    float3 response = evaluateClosureResponse(surface, v, wi, responsePhat);
    response *= intensity / (dist2 * metersPerWorldUnit * metersPerWorldUnit);
    phat = luminance(response);
    return response;
}

// Illuminance is integrated over a uniform-radiance angular cone. Its normalized solid-angle sampler
// cancels to E*2/(1+cos(alpha)); the delta-direction limit is exactly one.
public float3 evalDistantLightContrib(float3 lightDirection, float3 illuminance, float outerCos,
        float3 v, SurfaceClosure surface, out float phat) {
    float angularWeight = 2.0 / (1.0 + outerCos);
    float responsePhat;
    float3 response = evaluateClosureResponse(surface, v, lightDirection, responsePhat)
            * illuminance * angularWeight;
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

// Rectangle shape sampling is uniform in area; multiplying by area cancels that continuous PDF.
public float3 evalSampleContrib(float3 sp, float3 lnrm, float3 le, float area, float3 hitPos,
        float3 v, SurfaceClosure surface, out float phat) {
    phat = 0.0;
    float3 toL = sp - hitPos;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-6) return float3(0.0);
    float3 wi = toL * rsqrt(dist2);
    float cosL = max(0.0, dot(lnrm, -wi));
    if (cosL <= 0.0) return float3(0.0);
    float responsePhat;
    float3 contrib = evaluateClosureResponse(surface, v, wi, responsePhat)
            * le * (cosL * area / dist2);
    phat = luminance(contrib);
    return contrib;
}

// Fraction of the stratum proposal driven by the importance estimate below. The remainder is spread
// uniformly over the strata that hold a light, which bounds every stratum's probability from below by
// (1 - this) / populated. The floor is load-bearing, not caution: the strata are bounded by completely
// different structures — a world-sized emitter cluster against a 100 klx delta source — so no single
// estimate ranks them reliably, and a stratum driven toward zero probability shows up as fireflies at
// enormous weights rather than as a visibly wrong image.
static const float STRATUM_IMPORTANCE_SHARE = 0.75;

// Illuminance in lux that the node's subtree can be expected to deliver to the shading point.
//
// Peak intensity rather than emitted power is what makes rectangles, points and spots comparable on one
// scale, and makes the result directly comparable to a distant light's illuminance metric.
//
// Distance is measured to the node's CENTRE, not to the nearest point on its bounds. The nearest point
// is the conservative upper bound, but it is zero for every node that contains the shading point, and
// in a Morton-ordered tree sibling bounds overlap heavily, so near the root both children usually
// contain it. A metric that collapses there ranks the two by intensity alone and hands the candidates
// to whichever subtree is brightest in total rather than whichever is close — under inverse-square
// falloff that is worse than sampling uniformly, and it starves exactly the emitters whose direct-hit
// emission this estimator has taken responsibility for. It also penalises a near cluster for being
// large while a distant one still gets to use its real distance, which inverts the two outright.
//
// The clamp is half the node's own bounding-sphere radius: a shading point at the centre of a cluster
// really is about a cluster-radius away from its lights, and the clamp is what keeps a world-sized root
// bounded instead of diverging.
float finiteNodeImportance(GpuLightNode node, float3 hitPosRelative,
        float metersPerWorldUnit) {
    float3 radius = 0.5 * (node.boundsMax - node.boundsMin);
    float3 delta = hitPosRelative - (node.boundsMin + radius);
    float distance2 = max(dot(delta, delta), 0.25 * dot(radius, radius));
    return max(0.0, node.peakIntensityCandela)
            / max(distance2 * metersPerWorldUnit * metersPerWorldUnit, 1.0e-6);
}

float rootImportance(uint64_t nodeAddress, int rootIndex, float3 hitPosRelative,
        float metersPerWorldUnit) {
    if (nodeAddress == 0 || rootIndex < 0) return 0.0;
    GpuLightNode root = ConstPtr<GpuLightNode>(nodeAddress)[uint(rootIndex)];
    return finiteNodeImportance(root, hitPosRelative, metersPerWorldUnit);
}

bool traverseFinite(uint64_t lightAddress, uint64_t nodeAddress, int rootIndex,
        float3 hitPosRelative, float metersPerWorldUnit, float rootPdf,
        inout uint proposalSeed, out GpuLight light, out float sourcePdf) {
    sourcePdf = rootPdf;
    int nodeIndex = rootIndex;
    while (nodeIndex >= 0) {
        GpuLightNode node = ConstPtr<GpuLightNode>(nodeAddress)[uint(nodeIndex)];
        if (node.lightIndex >= 0) {
            light = ConstPtr<GpuLight>(lightAddress)[uint(node.lightIndex)];
            return sourcePdf > 0.0;
        }
        GpuLightNode left = ConstPtr<GpuLightNode>(nodeAddress)[uint(node.left)];
        GpuLightNode right = ConstPtr<GpuLightNode>(nodeAddress)[uint(node.right)];
        float leftWeight = finiteNodeImportance(left, hitPosRelative, metersPerWorldUnit);
        float rightWeight = finiteNodeImportance(right, hitPosRelative, metersPerWorldUnit);
        float sum = leftWeight + rightWeight;
        if (!(sum > 0.0)) return false;
        bool chooseLeft = rndf(proposalSeed) * sum < leftWeight;
        float selectedWeight = chooseLeft ? leftWeight : rightWeight;
        sourcePdf *= selectedWeight / sum;
        nodeIndex = chooseLeft ? node.left : node.right;
    }
    return false;
}

// Stratum probabilities for one shading point. Every field depends on the point alone, so this is built
// once per vertex and shared by all of that vertex's RIS candidates.
struct LightStrata {
    float retainedProbability;
    float frameProbability;
    float distantProbability;
    float distantWeightSum;
    bool populated;
};

LightStrata lightStrata(float3 hitPos) {
    LightStrata strata;
    strata.retainedProbability = 0.0;
    strata.frameProbability = 0.0;
    strata.distantProbability = 0.0;
    strata.distantWeightSum = 0.0;

    float retainedWeight = rootImportance(pc.retainedLightNodeAddr,
            worldPush.retainedLightInfo.x, hitPos - worldPush.retainedLightTransform.xyz,
            worldPush.retainedLightTransform.w);
    float frameWeight = rootImportance(pc.frameLightNodeAddr, worldPush.frameLightInfo.x,
            hitPos, worldPush.frameMetersPerWorldUnit);
    if (pc.frameLightAddr != 0) {
        for (int i = 0; i < worldPush.frameLightInfo.w; i++) {
            GpuLight distant = ConstPtr<GpuLight>(pc.frameLightAddr)[
                    uint(worldPush.frameLightInfo.z + i)];
            strata.distantWeightSum += max(0.0, distant.selectionMetric);
        }
    }
    float totalWeight = retainedWeight + frameWeight + strata.distantWeightSum;
    strata.populated = totalWeight > 0.0;
    if (!strata.populated) return strata;

    // A stratum's weight is zero exactly when it holds no samplable light: every record survives the
    // build only with positive intensity, and the distance term is finite. So counting positive weights
    // counts populated strata, and the floor is never spent on an empty one.
    float populatedCount = (retainedWeight > 0.0 ? 1.0 : 0.0) + (frameWeight > 0.0 ? 1.0 : 0.0)
            + (strata.distantWeightSum > 0.0 ? 1.0 : 0.0);
    float floorShare = (1.0 - STRATUM_IMPORTANCE_SHARE) / populatedCount;
    float scale = STRATUM_IMPORTANCE_SHARE / totalWeight;
    strata.retainedProbability = retainedWeight > 0.0 ? retainedWeight * scale + floorShare : 0.0;
    strata.frameProbability = frameWeight > 0.0 ? frameWeight * scale + floorShare : 0.0;
    strata.distantProbability = strata.distantWeightSum > 0.0
            ? strata.distantWeightSum * scale + floorShare : 0.0;
    return strata;
}

bool selectSceneLight(LightStrata strata, float3 hitPos, inout uint proposalSeed, out GpuLight light,
        out float3 segmentRebase, out float metersPerWorldUnit, out float sourcePdf) {
    float choice = rndf(proposalSeed);
    if (choice < strata.retainedProbability) {
        segmentRebase = worldPush.retainedLightTransform.xyz;
        metersPerWorldUnit = worldPush.retainedLightTransform.w;
        return traverseFinite(pc.retainedLightAddr, pc.retainedLightNodeAddr,
                worldPush.retainedLightInfo.x, hitPos - segmentRebase, metersPerWorldUnit,
                strata.retainedProbability, proposalSeed, light, sourcePdf);
    }
    choice -= strata.retainedProbability;
    if (choice < strata.frameProbability) {
        segmentRebase = float3(0.0);
        metersPerWorldUnit = worldPush.frameMetersPerWorldUnit;
        return traverseFinite(pc.frameLightAddr, pc.frameLightNodeAddr,
                worldPush.frameLightInfo.x, hitPos, metersPerWorldUnit,
                strata.frameProbability, proposalSeed, light, sourcePdf);
    }
    choice -= strata.frameProbability;
    if (!(strata.distantProbability > 0.0)) return false;
    // Inside the distant stratum the proposal stays proportional to illuminance. These are a handful of
    // delta sources whose metric is the exact quantity the estimator wants, so there is nothing here for
    // a floor to protect against. Rescale the leftover probability into weight space to walk them.
    float target = choice * (strata.distantWeightSum / strata.distantProbability);
    for (int i = 0; i < worldPush.frameLightInfo.w; i++) {
        GpuLight distant = ConstPtr<GpuLight>(pc.frameLightAddr)[uint(worldPush.frameLightInfo.z + i)];
        float weight = max(0.0, distant.selectionMetric);
        if (target < weight || i == worldPush.frameLightInfo.w - 1) {
            light = distant;
            segmentRebase = float3(0.0);
            metersPerWorldUnit = worldPush.frameMetersPerWorldUnit;
            sourcePdf = strata.distantProbability * weight / strata.distantWeightSum;
            return sourcePdf > 0.0;
        }
        target -= weight;
    }
    return false;
}

public Reservoir risInitial(float3 hitPos, float3 v, SurfaceClosure surface,
        inout uint seed, inout uint proposalSeed) {
    Reservoir r = resEmpty();
    uint candidateCount = worldPush.risCandidates;
    r.M = float(candidateCount);
    LightStrata strata = lightStrata(hitPos);
    if (!strata.populated) return r;
    for (uint c = 0u; c < candidateCount; c++) {
        GpuLight light;
        float3 segmentRebase;
        float metersPerWorldUnit;
        float sourcePdf;
        if (!selectSceneLight(strata, hitPos, proposalSeed, light, segmentRebase,
                metersPerWorldUnit, sourcePdf)) continue;

        float3 samplePos = light.pos + segmentRebase;
        float3 sampleNormal = light.axis0;
        float sampleArea = light.range;
        if (light.type == 0u) {
            samplePos += (rndf(seed) * 2.0 - 1.0) * light.axis0
                    + (rndf(seed) * 2.0 - 1.0) * light.axis1;
            sampleNormal = normalize(cross(light.axis0, light.axis1)) * light.shapeScalar;
            sampleArea = 4.0 * length(cross(light.axis0, light.axis1));
        } else if (light.type == 3u) {
            sampleNormal = sampleUniformCone(light.axis0, light.shapeScalar, seed);
        }

        float phat;
        if (light.type == 0u) {
            evalSampleContrib(samplePos, sampleNormal, light.radiometry, sampleArea,
                    hitPos, v, surface, phat);
        } else if (light.type == 3u) {
            evalDistantLightContrib(sampleNormal, light.radiometry, light.shapeScalar,
                    v, surface, phat);
        } else {
            evalDeltaLightContrib(samplePos, light.axis0, light.radiometry, light.range,
                    light.shapeScalar, metersPerWorldUnit, light.type, hitPos, v, surface, phat);
        }
        if (phat <= 0.0 || sourcePdf <= 0.0) continue;
        float weight = phat / max(sourcePdf, 1.0e-20);
        r.wSum += weight;
        if (rndf(seed) * r.wSum < weight) {
            r.pos = samplePos;
            r.lnrm = sampleNormal;
            r.le = light.radiometry;
            r.area = sampleArea;
            r.phat = phat;
            r.lightType = light.type;
            r.shapeScalar = light.shapeScalar;
            r.metersPerWorldUnit = metersPerWorldUnit;
        }
    }
    r.W = r.phat > 0.0 ? r.wSum / (r.M * r.phat) : 0.0;
    return r;
}

public float3 shadeReservoir<TSurfaces : ISurfaceDispatch>(TSurfaces surfaces, Reservoir s,
        float3 hitPos, float3 n, float3 v, SurfaceClosure surface, Medium currentMedium) {
    if (s.W <= 0.0 || s.phat <= 0.0) return float3(0.0);
    float phat;
    float3 contrib;
    if (s.lightType == 0u) {
        contrib = evalSampleContrib(s.pos, s.lnrm, s.le, s.area, hitPos, v, surface, phat);
    } else if (s.lightType == 3u) {
        contrib = evalDistantLightContrib(s.lnrm, s.le, s.shapeScalar, v, surface, phat);
    } else {
        contrib = evalDeltaLightContrib(s.pos, s.lnrm, s.le, s.area, s.shapeScalar,
                s.metersPerWorldUnit, s.lightType, hitPos, v, surface, phat);
    }
    if (phat <= 0.0) return float3(0.0);
    if (s.lightType == 3u) {
        float side = dot(n, s.lnrm) >= 0.0 ? 1.0 : -1.0;
        float3 origin = hitPos + side * n * SURF_BIAS;
        bool hasExtinction = any(currentMedium.extinction > 0.0);
        bool boundaryLighting = (currentMedium.flags & MEDIUM_BOUNDARY_LIGHTING) != 0u;
        VisibilityResult shadow = visibility(origin, s.lnrm, RAY_TMAX,
                hasExtinction || boundaryLighting, currentMedium.implementation);
        float3 vis = shadow.transmittance;
        if (hasExtinction) {
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
            input.surfaceFlags = (worldPush.flags & 16u) != 0u ? SURFACE_ANIMATION_ENABLED : 0u;
            vis *= surfaces.evaluateMediumLighting(currentMedium.implementation, input);
        }
        return contrib * vis * s.W;
    }
    float side = dot(n, s.pos - hitPos) >= 0.0 ? 1.0 : -1.0;
    float3 origin = hitPos + side * n * SURF_BIAS;
    float3 toL = s.pos - origin;
    float dist = length(toL);
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
        input.surfaceFlags = (worldPush.flags & 16u) != 0u ? SURFACE_ANIMATION_ENABLED : 0u;
        vis *= surfaces.evaluateMediumLighting(currentMedium.implementation, input);
    }
    return contrib * vis * s.W;
}
