import math;
import world_common;
import world_core;

static const float3 FLAME_AXES = float3(0.065, 0.12, 0.065);
static const float FLAME_RANGE = 16.0;

public struct HeldFlameHit {
    public float distance; // Negative on a miss.
    public float3 radiance;
    public float3 motionPrev; // Current position minus previous position.
};

public struct HeldFlameSample {
    public float3 direction;
    public float distance;
    public float3 weightedRadiance; // Emitted radiance divided by the world-space solid-angle PDF.
};

float3 flameRadiance(float4 torch, float distanceSquared) {
    float3 intensity = torch.w == 2.0 ? float3(0.15, 0.55, 1.0)
                       : torch.w == 3.0 ? float3(0.32, 0.025, 0.01)
                       : torch.w == 4.0 ? float3(1.4, 3.9, 2.2) : float3(5.4, 2.7, 0.93);
    // A uniform ellipsoid's side-on intensity is radiance times its projected area.
    float sideArea = PI * FLAME_AXES.x * FLAME_AXES.y;
    float fade = 1.0 - smoothstep(12.0, FLAME_RANGE, sqrt(distanceSquared));
    return bt709ToAcesCg(intensity) * (4.0 * fade / sideArea);
}

float intersectFlame(float3 toCenter, float3 direction) {
    float3 localCenter = toCenter / FLAME_AXES;
    float3 localDirection = direction / FLAME_AXES;
    float inverseSpeed = rsqrt(dot(localDirection, localDirection));
    localDirection *= inverseSpeed;
    float projected = dot(localCenter, localDirection);
    float3 perpendicular = localCenter - projected * localDirection;
    float discriminant = 1.0 - dot(perpendicular, perpendicular);
    if (discriminant < 0.0) return -1.0;
    float radiusAlongRay = sqrt(discriminant);
    float nearT = (projected - radiusAlongRay) * inverseSpeed;
    float farT = (projected + radiusAlongRay) * inverseSpeed;
    return nearT > 0.0 ? nearT : (farT > 0.0 ? farT : -1.0);
}

void considerFlame(float4 torch, float4 previousTorch, float3 origin, float3 direction, float tMax,
                   inout HeldFlameHit hit) {
    if (torch.w == 0.0) return;
    float3 toCenter = torch.xyz - origin;
    float distanceSquared = dot(toCenter, toCenter);
    if (distanceSquared >= FLAME_RANGE * FLAME_RANGE) return;
    float distance = intersectFlame(toCenter, direction);
    if (distance <= 0.0 || distance >= tMax || (hit.distance >= 0.0 && distance >= hit.distance)) return;
    hit.distance = distance;
    hit.radiance = flameRadiance(torch, distanceSquared);
    if (previousTorch.w == torch.w) {
        hit.motionPrev = torch.xyz - previousTorch.xyz;
    } else {
        hit.motionPrev = float3(0.0);
    }
}

public HeldFlameHit traceHeldFlames(float3 ro, float3 rd, float tMax) {
    HeldFlameHit hit = {};
    hit.distance = -1.0;
    considerFlame(worldPush.mainHandTorch, worldPush.prevMainHandTorch, ro, rd, tMax, hit);
    considerFlame(worldPush.offHandTorch, worldPush.prevOffHandTorch, ro, rd, tMax, hit);
    return hit;
}

public HeldFlameSample sampleHeldFlame(float4 torch, float3 receiver, inout uint seed) {
    HeldFlameSample sample = {};
    sample.distance = -1.0;
    if (torch.w == 0.0) return sample;
    float3 toCenter = torch.xyz - receiver;
    float distanceSquared = dot(toCenter, toCenter);
    if (distanceSquared >= FLAME_RANGE * FLAME_RANGE) return sample;

    // In stretched coordinates the ellipsoid is a unit sphere. Sample its visible cone, or the
    // whole sphere of directions when the receiver lies inside the flame.
    float3 localCenter = toCenter / FLAME_AXES;
    float centerDistanceSquared = dot(localCenter, localCenter);
    float3 axis = float3(0.0, 1.0, 0.0);
    float coneWidth = 2.0;
    if (centerDistanceSquared > 1.0) {
        float sinThetaMaxSquared = 1.0 / centerDistanceSquared;
        axis = localCenter * sqrt(sinThetaMaxSquared);
        // 1 - cos(thetaMax), evaluated without cancellation for a nearly point-sized flame.
        coneWidth = sinThetaMaxSquared / (1.0 + sqrt(1.0 - sinThetaMaxSquared));
    }
    float3 reference = abs(axis.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
    float3 bitangent = normalize(cross(axis, reference));
    float3 tangent = cross(bitangent, axis);
    float oneMinusCos = rndf(seed) * coneWidth;
    float sinTheta = sqrt(max(0.0, oneMinusCos * (2.0 - oneMinusCos)));
    float phi = 2.0 * PI * rndf(seed);
    float3 localDirection = (1.0 - oneMinusCos) * axis
            + sinTheta * (cos(phi) * tangent + sin(phi) * bitangent);
    float3 worldDirection = localDirection * FLAME_AXES;
    float worldLength = length(worldDirection);
    sample.direction = worldDirection / worldLength;
    sample.distance = intersectFlame(toCenter, sample.direction);
    if (sample.distance <= 0.0) return sample;

    // A stretch A maps differential solid angle by det(A)/|A*d|^3. Inverting the resulting
    // world-space PDF therefore multiplies the sampled radiance by this angular Jacobian.
    float determinant = FLAME_AXES.x * FLAME_AXES.y * FLAME_AXES.z;
    float inversePdf = 2.0 * PI * coneWidth * determinant / (worldLength * worldLength * worldLength);
    sample.weightedRadiance = flameRadiance(torch, distanceSquared) * inversePdf;
    return sample;
}
