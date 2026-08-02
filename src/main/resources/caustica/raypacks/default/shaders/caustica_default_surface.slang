module caustica_default_surface;

import caustica_ray_pack_api;

static const float PI = 3.14159265359;
static const float INV_PI = 0.31830988618;

float3 defaultFresnelSchlick(float cosine, float3 f0) {
    float m = 1.0 - clamp(cosine, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}

float defaultGgxD(float ndh, float alpha) {
    float a2 = alpha * alpha;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / max(PI * d * d, 1.0e-7);
}

float defaultGgxG1(float ndx, float alpha) {
    float a2 = alpha * alpha;
    return 2.0 * ndx / max(ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx), 1.0e-7);
}

void defaultBasis(float3 n, out float3 tangent, out float3 bitangent) {
    float3 up = abs(n.z) < 0.999 ? float3(0.0, 0.0, 1.0) : float3(1.0, 0.0, 0.0);
    tangent = normalize(cross(up, n));
    bitangent = cross(n, tangent);
}

float3 defaultLocalDirection(float3 n, float phi, float sinTheta, float cosTheta) {
    float3 tangent;
    float3 bitangent;
    defaultBasis(n, tangent, bitangent);
    return normalize(tangent * (cos(phi) * sinTheta)
            + bitangent * (sin(phi) * sinTheta) + n * cosTheta);
}

float defaultSpecularProbability(PackSurfaceClosure surface) {
    float f0 = max(surface.specularF0.x, max(surface.specularF0.y, surface.specularF0.z));
    return clamp(f0, 0.05, 0.95);
}

public float defaultCoverage(PackSurfaceInput input) {
    return clamp(input.material.opacity, 0.0, 1.0);
}

public PackSurfaceClosure defaultCreateSurface(PackSurfaceInput input) {
    PackSurfaceClosure surface;
    surface.shadingNormal = normalize(input.shadingNormal);
    surface.diffuseAlbedo = input.material.baseColor * (1.0 - input.material.metalness)
            * input.material.ambientOcclusion;
    surface.specularF0 = clamp(input.material.specularF0, float3(0.0), float3(1.0));
    surface.alphaRoughness = max(input.material.alphaRoughness, 4.0e-4);
    surface.emissionRadiance = max(input.material.emissionRadiance, float3(0.0));
    surface.transmission = clamp(input.material.transmission, 0.0, 1.0);
    surface.interiorIor = max(input.material.interiorIor, 1.0);
    surface.subsurface = clamp(input.material.subsurface, 0.0, 1.0);
    surface.flags = input.surfaceFlags;
    return surface;
}

public PackBsdfEvaluation defaultEvaluateBsdf(PackBsdfQuery query) {
    PackBsdfEvaluation result;
    result.value = float3(0.0);
    result.pdf = 0.0;
    result.eventFlags = PACK_EVENT_NONE;

    float3 n = query.surface.shadingNormal;
    float ndv = dot(n, query.outgoingDirection);
    float ndl = dot(n, query.incomingDirection);
    if ((query.surface.flags & PACK_SURFACE_TWO_SIDED) != 0u) {
        ndv = abs(ndv);
        ndl = abs(ndl);
    }
    if (ndv <= 0.0 || ndl <= 0.0 || query.surface.transmission > 0.0) return result;

    float3 halfVector = normalize(query.incomingDirection + query.outgoingDirection);
    float ndh = max(0.0, dot(n, halfVector));
    float vdh = max(0.0, dot(query.outgoingDirection, halfVector));
    float alpha = query.surface.alphaRoughness;
    float distribution = defaultGgxD(ndh, alpha);
    float geometry = defaultGgxG1(ndv, alpha) * defaultGgxG1(ndl, alpha);
    float3 fresnel = defaultFresnelSchlick(vdh, query.surface.specularF0);
    float3 diffuse = query.surface.diffuseAlbedo * INV_PI;
    float3 specular = distribution * geometry * fresnel / max(4.0 * ndv * ndl, 1.0e-6);
    float specularProbability = defaultSpecularProbability(query.surface);
    float diffusePdf = ndl * INV_PI;
    float specularPdf = distribution * ndh / max(4.0 * vdh, 1.0e-6);

    result.value = diffuse + specular;
    result.pdf = lerp(diffusePdf, specularPdf, specularProbability);
    result.eventFlags = PACK_EVENT_DIFFUSE | PACK_EVENT_GLOSSY | PACK_EVENT_REFLECTION;
    return result;
}

// The engine never calls this for a transmissive closure (transmission > 0) — it routes those through
// its own dielectric interface (engineSampleDielectricInterface) instead, since Fresnel choice,
// reflect/refract, and the medium a ray continues into are engine-owned in full.
public PackBsdfSample defaultSampleBsdf(PackBsdfSampleQuery query) {
    PackBsdfSample result;
    result.incomingDirection = float3(0.0);
    result.throughputWeight = float3(0.0);
    result.pdf = 0.0;
    result.eventFlags = PACK_EVENT_NONE;

    float3 n = query.surface.shadingNormal;
    float ndv = max(0.0, dot(n, query.outgoingDirection));
    if (ndv <= 0.0) return result;

    float specularProbability = defaultSpecularProbability(query.surface);
    float selector = query.random.x;
    if (selector < specularProbability) {
        float alpha = query.surface.alphaRoughness;
        float phi = 2.0 * PI * query.random.y;
        float tanTheta2 = alpha * alpha * query.random.z / max(1.0 - query.random.z, 1.0e-6);
        float cosTheta = rsqrt(1.0 + tanTheta2);
        float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
        float3 halfVector = defaultLocalDirection(n, phi, sinTheta, cosTheta);
        result.incomingDirection = reflect(-query.outgoingDirection, halfVector);
    } else {
        float phi = 2.0 * PI * query.random.y;
        float sinTheta = sqrt(query.random.z);
        float cosTheta = sqrt(max(0.0, 1.0 - query.random.z));
        result.incomingDirection = defaultLocalDirection(n, phi, sinTheta, cosTheta);
    }

    if (dot(n, result.incomingDirection) <= 0.0) return result;
    PackBsdfQuery evaluationQuery;
    evaluationQuery.surface = query.surface;
    evaluationQuery.outgoingDirection = query.outgoingDirection;
    evaluationQuery.incomingDirection = result.incomingDirection;
    PackBsdfEvaluation evaluation = defaultEvaluateBsdf(evaluationQuery);
    result.pdf = evaluation.pdf;
    result.throughputWeight = evaluation.value
            * abs(dot(n, result.incomingDirection)) / max(evaluation.pdf, 1.0e-7);
    result.eventFlags = selector < specularProbability
            ? PACK_EVENT_GLOSSY | PACK_EVENT_REFLECTION
            : PACK_EVENT_DIFFUSE | PACK_EVENT_REFLECTION;
    return result;
}

// Identity: the reference look applies no stylization to a shaded contribution.
public float3 defaultEvaluateResponse(float3 radiance, PackSurfaceClosure surface) {
    return radiance;
}
