module caustica_builtin_surface;

import caustica_types;
import caustica_surface;

static const float PI = 3.14159265359;
static const float INV_PI = 0.31830988618;

// Schlick's grazing limit is white, which is wrong for a surface that has no interface at all: a
// billboard authored at specular_ior 1 has F0 = 0 and must stay matte edge-on rather than picking up a
// rim. Scaling the limit by F0 keeps every real material (F0 >= 0.02) at the usual full-white grazing
// response and collapses the lobe only where there is genuinely nothing to reflect off.
float3 defaultFresnelSchlick(float cosine, float3 f0) {
    float m = 1.0 - clamp(cosine, 0.0, 1.0);
    float m2 = m * m;
    float grazing = clamp(50.0 * f0.g, 0.0, 1.0);
    return f0 + (grazing - f0) * (m2 * m2 * m);
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

// Zero reflectance means there is no interface, so no share of the samples goes to a lobe that would
// evaluate to nothing; every other surface keeps a useful floor.
float defaultSpecularProbability(SurfaceClosure surface) {
    float f0 = max(surface.specularF0.x, max(surface.specularF0.y, surface.specularF0.z));
    return f0 <= 0.0 ? 0.0 : clamp(f0, 0.05, 0.95);
}

float defaultCoverage(SurfaceInput input) {
    return clamp(input.material.geometryOpacity, 0.0, 1.0);
}

SurfaceClosure defaultCreateSurface(SurfaceInput input) {
    // OpenPBR r -> GGX alpha. The floor is the smoothest lobe the sampler resolves without collapsing to
    // a delta the estimator has no event for.
    float roughness = clamp(input.material.specularRoughness, 0.0, 1.0);
    SurfaceClosure surface;
    surface.shadingNormal = normalize(input.shadingNormal);
    surface.diffuseAlbedo = input.material.baseColor * (1.0 - input.material.baseMetalness);
    surface.specularF0 = clamp(openPbrSpecularF0(input.material), float3(0.0), float3(1.0));
    surface.alphaRoughness = max(roughness * roughness, 4.0e-4);
    surface.emissionRadiance = max(input.material.emissionColor, float3(0.0))
            * max(input.material.emissionLuminance, 0.0);
    // Only a thin wall transmits diffusely; a volume's transmission is refractive and never reaches a
    // closure, so it would be a different lobe entirely.
    surface.transmission = input.material.geometryThinWalled
            ? clamp(input.material.transmissionWeight, 0.0, 1.0) : 0.0;
    surface.interiorIor = max(input.material.specularIor, 1.0);
    surface.flags = input.surfaceFlags;
    return surface;
}

// One evaluation covering both hemispheres. The shading normal points toward the viewer, so an incoming
// direction on the far side is a diffuse transmission through the thin wall and one on the near side is
// ordinary reflection; the two are mutually exclusive for a given direction, which is why a single
// shadow ray serves either.
BsdfEvaluation defaultEvaluateBsdf(BsdfQuery query) {
    BsdfEvaluation result;
    result.value = float3(0.0);
    result.pdf = 0.0;
    result.eventFlags = EVENT_NONE;

    float3 n = query.surface.shadingNormal;
    float ndv = dot(n, query.outgoingDirection);
    float ndl = dot(n, query.incomingDirection);
    if (ndv <= 0.0) return result;

    float transmission = query.surface.transmission;
    float specularProbability = defaultSpecularProbability(query.surface);
    if (ndl <= 0.0) {
        if (transmission <= 0.0) return result;
        float backNdl = -ndl;
        result.value = query.surface.diffuseAlbedo * transmission * INV_PI;
        result.pdf = backNdl * INV_PI * (1.0 - specularProbability) * transmission;
        result.eventFlags = EVENT_DIFFUSE | EVENT_TRANSMISSION;
        return result;
    }

    float3 halfVector = normalize(query.incomingDirection + query.outgoingDirection);
    float ndh = max(0.0, dot(n, halfVector));
    float vdh = max(0.0, dot(query.outgoingDirection, halfVector));
    float alpha = query.surface.alphaRoughness;
    float distribution = defaultGgxD(ndh, alpha);
    float geometry = defaultGgxG1(ndv, alpha) * defaultGgxG1(ndl, alpha);
    float3 fresnel = defaultFresnelSchlick(vdh, query.surface.specularF0);
    float3 diffuse = query.surface.diffuseAlbedo * (1.0 - transmission) * INV_PI;
    float3 specular = distribution * geometry * fresnel / max(4.0 * ndv * ndl, 1.0e-6);
    float diffusePdf = ndl * INV_PI * (1.0 - specularProbability) * (1.0 - transmission);
    float specularPdf = distribution * ndh / max(4.0 * vdh, 1.0e-6) * specularProbability;

    result.value = diffuse + specular;
    result.pdf = diffusePdf + specularPdf;
    result.eventFlags = EVENT_DIFFUSE | EVENT_GLOSSY | EVENT_REFLECTION;
    return result;
}

// A refractive closure never reaches here: the engine routes volume transmission through its own
// dielectric interface, since Fresnel choice, reflect/refract, and the medium a ray continues into are
// engine-owned in full. The transmission this samples is the thin wall's diffuse one.
BsdfSample defaultSampleBsdf(BsdfSampleQuery query) {
    BsdfSample result;
    result.incomingDirection = float3(0.0);
    result.throughputWeight = float3(0.0);
    result.pdf = 0.0;
    result.eventFlags = EVENT_NONE;

    float3 n = query.surface.shadingNormal;
    float ndv = max(0.0, dot(n, query.outgoingDirection));
    if (ndv <= 0.0) return result;

    // Three lobes, selected with the same weights the pdf above assumes: specular takes its share first,
    // then the diffuse remainder splits between the two sides by the transmission weight.
    float specularProbability = defaultSpecularProbability(query.surface);
    float transmissionProbability = (1.0 - specularProbability) * query.surface.transmission;
    float selector = query.random.x;
    bool transmitted = false;
    if (selector < specularProbability) {
        float alpha = query.surface.alphaRoughness;
        float phi = 2.0 * PI * query.random.y;
        float tanTheta2 = alpha * alpha * query.random.z / max(1.0 - query.random.z, 1.0e-6);
        float cosTheta = rsqrt(1.0 + tanTheta2);
        float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
        float3 halfVector = defaultLocalDirection(n, phi, sinTheta, cosTheta);
        result.incomingDirection = reflect(-query.outgoingDirection, halfVector);
    } else {
        transmitted = selector < specularProbability + transmissionProbability;
        float phi = 2.0 * PI * query.random.y;
        float sinTheta = sqrt(query.random.z);
        float cosTheta = sqrt(max(0.0, 1.0 - query.random.z));
        result.incomingDirection = defaultLocalDirection(transmitted ? -n : n, phi, sinTheta, cosTheta);
    }

    float sampledNdl = dot(n, result.incomingDirection);
    if (transmitted ? sampledNdl >= 0.0 : sampledNdl <= 0.0) return result;
    BsdfQuery evaluationQuery;
    evaluationQuery.surface = query.surface;
    evaluationQuery.outgoingDirection = query.outgoingDirection;
    evaluationQuery.incomingDirection = result.incomingDirection;
    BsdfEvaluation evaluation = defaultEvaluateBsdf(evaluationQuery);
    result.pdf = evaluation.pdf;
    result.throughputWeight = evaluation.value
            * abs(sampledNdl) / max(evaluation.pdf, 1.0e-7);
    // The lobe that was actually sampled, not the union the evaluation reports: the engine reads
    // EVENT_DIFFUSE to decide whether this bounce may still see the celestial disc.
    result.eventFlags = transmitted ? (EVENT_DIFFUSE | EVENT_TRANSMISSION)
            : (selector < specularProbability ? (EVENT_GLOSSY | EVENT_REFLECTION)
                                              : (EVENT_DIFFUSE | EVENT_REFLECTION));
    return result;
}

// Identity: the reference look applies no stylization to a shaded contribution.
float3 defaultEvaluateResponse(float3 radiance, SurfaceClosure surface) {
    return radiance;
}

public struct BuiltinSurface : ISurfaceModel {
    public float evaluateCoverage(SurfaceInput input) {
        return defaultCoverage(input);
    }

    public SurfaceClosure createSurface(SurfaceInput input) {
        return defaultCreateSurface(input);
    }

    public BsdfEvaluation evaluateBsdf(BsdfQuery query) {
        return defaultEvaluateBsdf(query);
    }

    public BsdfSample sampleBsdf(BsdfSampleQuery query) {
        return defaultSampleBsdf(query);
    }

    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface) {
        return defaultEvaluateResponse(radiance, surface);
    }
};
