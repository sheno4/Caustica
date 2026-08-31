// The engine's one OpenPBR BSDF: authored parameters become a closure here, and direct and indirect
// lighting both evaluate this same closure. Keeping lobe values and PDFs together is what makes their
// mixture weights load-bearing rather than conventions duplicated by each lighting backend.
module surface_bsdf;

import world_common;
import caustica_types;

static const float SURFACE_PI = 3.14159265359;
static const float SURFACE_INV_PI = 0.31830988618;

float surfaceGgxD(float ndh, float alpha) {
    float a2 = alpha * alpha;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (SURFACE_PI * d * d);
}

float surfaceGgxG1(float ndx, float alpha) {
    float a2 = alpha * alpha;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

float3 surfaceFresnelSchlick(float cosine, float3 f0) {
    float m = clamp(1.0 - cosine, 0.0, 1.0);
    float m2 = m * m;
    float grazing = clamp(50.0 * f0.g, 0.0, 1.0);
    return f0 + (grazing - f0) * (m2 * m2 * m);
}

float3 surfaceLayerTransmission(float3 f0, float outgoingCosine, float incomingCosine) {
    return max((1.0 - surfaceFresnelSchlick(clamp(outgoingCosine, 0.0, 1.0), f0))
            * (1.0 - surfaceFresnelSchlick(clamp(incomingCosine, 0.0, 1.0), f0)), float3(0.0));
}

public static const uint EVENT_NONE = 0u;
public static const uint EVENT_DIFFUSE = 1u;
public static const uint EVENT_GLOSSY = 2u;
public static const uint EVENT_REFLECTION = 4u;
public static const uint EVENT_TRANSMISSION = 8u;

public struct SurfaceClosure {
    public float3 shadingNormal;
    public float3 diffuseAlbedo;
    public float3 diffuseTransmissionAlbedo;
    public float3 specularF0;
    public float alphaRoughness;
    public float3 emissionRadiance;
    public float3 thinTransmissionColor;
    public float thinTransmissionWeight;
};

public struct BsdfQuery {
    public SurfaceClosure surface;
    public float3 outgoingDirection;
    public float3 incomingDirection;
};

public struct BsdfEvaluation {
    public float3 value;
    public float3 diffuseValue;
    public float3 specularValue;
    public float diffusePdf;
    public float specularPdf;
    public float pdf;
    public uint eventFlags;
};
public struct BsdfSampleQuery {
    public SurfaceClosure surface;
    public float3 outgoingDirection;
    public float3 random;
};
public struct BsdfSample {
    public float3 incomingDirection;
    public float3 throughputWeight;
    public float pdf;
    public uint eventFlags;
};

float maxComponent(float3 value) {
    return max(value.x, max(value.y, value.z));
}

void surfaceBasis(float3 n, out float3 tangent, out float3 bitangent) {
    float3 up = abs(n.z) < 0.999 ? float3(0.0, 0.0, 1.0) : float3(1.0, 0.0, 0.0);
    tangent = normalize(cross(up, n));
    bitangent = cross(n, tangent);
}

float3 surfaceLocalDirection(float3 n, float phi, float sinTheta, float cosTheta) {
    float3 tangent;
    float3 bitangent;
    surfaceBasis(n, tangent, bitangent);
    return normalize(tangent * (cos(phi) * sinTheta)
            + bitangent * (sin(phi) * sinTheta) + n * cosTheta);
}

/**
 * Reflection and straight-through transmission of an infinitesimally thin absorbing dielectric sheet.
 * The geometric series is the smooth OpenPBR thin-wall solution evaluated at the microfacet Fresnel
 * factor. Using it on the rough GGX microfacets is the supported rough-sheet approximation.
 */
void thinSheetFactors(float3 fresnel, float3 transmittance,
        out float3 reflection, out float3 transmission) {
    float3 f = clamp(fresnel, float3(0.0), float3(1.0));
    float3 a = clamp(transmittance, float3(0.0), float3(1.0));
    float3 oneMinusF = float3(1.0) - f;
    float3 denominator = max(float3(1.0) - f * f * a * a, float3(1.0e-6));
    reflection = f + oneMinusF * oneMinusF * f * a * a / denominator;
    transmission = oneMinusF * oneMinusF * a / denominator;
}

/** Resolve the supported OpenPBR base into the exact albedos and sheet state its lobes consume. */
public SurfaceClosure surfaceClosure(OpenPbrSurface material, float3 geometryNormal) {
    float roughness = clamp(material.specular_roughness, 0.0, 1.0);
    float dielectricWeight = 1.0 - clamp(material.base_metalness, 0.0, 1.0);
    float transmissionWeight = clamp(material.transmission_weight, 0.0, 1.0);
    float subsurfaceWeight = clamp(material.subsurface_weight, 0.0, 1.0);
    float anisotropy = clamp(material.subsurface_scatter_anisotropy, -1.0, 1.0);
    float opaqueDielectricWeight = dielectricWeight * (1.0 - transmissionWeight);
    float3 baseColor = clamp(material.base_color, float3(0.0), float3(1.0));
    float3 subsurfaceColor = clamp(material.subsurface_color, float3(0.0), float3(1.0));

    // OpenPBR 1.1.1 thin-wall subsurface degeneration: S is split exactly between a reflected and a
    // transmitted Lambert lobe by g. Their sum is S, so the sheet remains energy conserving.
    float3 diffuseReflection = (1.0 - subsurfaceWeight) * baseColor
            + subsurfaceWeight * subsurfaceColor * (0.5 * (1.0 - anisotropy));
    float3 diffuseTransmission = subsurfaceWeight * subsurfaceColor
            * (0.5 * (1.0 + anisotropy));

    SurfaceClosure surface;
    surface.shadingNormal = normalize(any(material.geometry_normal != 0.0)
            ? material.geometry_normal : geometryNormal);
    surface.diffuseAlbedo = opaqueDielectricWeight
            * (diffuseReflection + diffuseTransmission);
    surface.diffuseTransmissionAlbedo = opaqueDielectricWeight * diffuseTransmission;
    surface.specularF0 = clamp(openPbrSpecularF0(material), float3(0.0), float3(1.0));
    surface.alphaRoughness = max(roughness * roughness, 4.0e-4);
    surface.emissionRadiance = max(material.emission_color, float3(0.0))
            * max(material.emission_luminance, 0.0);
    surface.thinTransmissionColor = clamp(material.transmission_color,
            float3(0.0), float3(1.0));
    surface.thinTransmissionWeight = dielectricWeight * transmissionWeight;
    return surface;
}

// Probabilities are scalar importance estimates; RGB lobe values remain untouched. Evaluation and
// sampling call this same function, so changing the heuristic cannot create a value/pdf disagreement.
public float4 surfaceLobeProbabilities(SurfaceClosure surface, float outgoingCosine) {
    float3 diffuseReflection = max(surface.diffuseAlbedo - surface.diffuseTransmissionAlbedo,
            float3(0.0));
    float3 fresnel = surfaceFresnelSchlick(outgoingCosine, surface.specularF0);
    float3 sheetReflection;
    float3 sheetTransmission;
    thinSheetFactors(fresnel, surface.thinTransmissionColor,
            sheetReflection, sheetTransmission);
    float thinWeight = clamp(surface.thinTransmissionWeight, 0.0, 1.0);

    float4 importance = float4(
            maxComponent(lerp(fresnel, sheetReflection, thinWeight)),
            maxComponent(diffuseReflection),
            maxComponent(surface.diffuseTransmissionAlbedo),
            maxComponent(sheetTransmission) * thinWeight);
    float total = importance.x + importance.y + importance.z + importance.w;
    if (total <= 0.0) return float4(0.0);
    float4 probabilities = importance / total;
    float glossyProbability = probabilities.x + probabilities.w;
    float diffuseProbability = probabilities.y + probabilities.z;
    if (glossyProbability > 0.0 && diffuseProbability > 0.0) {
        // A 1/16 grouped floor bounds first-lobe path weights while retaining the authored ratio
        // between reflection and transmission inside each grouped denoiser signal.
        float clampedDiffuseProbability = clamp(diffuseProbability, 1.0 / 16.0, 15.0 / 16.0);
        probabilities.xw *= (1.0 - clampedDiffuseProbability) / glossyProbability;
        probabilities.yz *= clampedDiffuseProbability / diffuseProbability;
    }
    return probabilities;
}

public void surfaceDenoiserEstimates(SurfaceClosure surface, float3 outgoingDirection,
        out float3 diffuseEstimate, out float3 specularEstimate) {
    float cosine = clamp(dot(surface.shadingNormal, outgoingDirection), 0.0, 1.0);
    float3 fresnel = surfaceFresnelSchlick(cosine, surface.specularF0);
    float3 sheetReflection;
    float3 sheetTransmission;
    thinSheetFactors(fresnel, surface.thinTransmissionColor,
            sheetReflection, sheetTransmission);
    float thinWeight = clamp(surface.thinTransmissionWeight, 0.0, 1.0);
    diffuseEstimate = max(surface.diffuseAlbedo * (float3(1.0) - fresnel), float3(0.0));
    specularEstimate = max(lerp(fresnel, sheetReflection, thinWeight)
            + sheetTransmission * thinWeight, float3(0.0));
}

public BsdfEvaluation evaluateBsdf(BsdfQuery query) {
    BsdfEvaluation result;
    result.value = float3(0.0);
    result.diffuseValue = float3(0.0);
    result.specularValue = float3(0.0);
    result.diffusePdf = 0.0;
    result.specularPdf = 0.0;
    result.pdf = 0.0;
    result.eventFlags = EVENT_NONE;

    float3 n = query.surface.shadingNormal;
    float ndv = dot(n, query.outgoingDirection);
    float ndl = dot(n, query.incomingDirection);
    if (ndv <= 0.0) return result;

    float4 probabilities = surfaceLobeProbabilities(query.surface, ndv);
    float alpha = query.surface.alphaRoughness;
    if (ndl > 0.0) {
        float3 halfVector = normalize(query.incomingDirection + query.outgoingDirection);
        float ndh = max(0.0, dot(n, halfVector));
        float vdh = max(0.0, dot(query.outgoingDirection, halfVector));
        float distribution = surfaceGgxD(ndh, alpha);
        float geometry = surfaceGgxG1(ndv, alpha) * surfaceGgxG1(ndl, alpha);
        float3 fresnel = surfaceFresnelSchlick(vdh, query.surface.specularF0);
        float3 sheetReflection;
        float3 sheetTransmission;
        thinSheetFactors(fresnel, query.surface.thinTransmissionColor,
                sheetReflection, sheetTransmission);
        float thinWeight = clamp(query.surface.thinTransmissionWeight, 0.0, 1.0);
        float3 reflectedFresnel = lerp(fresnel, sheetReflection, thinWeight);
        float3 layerTransmission = surfaceLayerTransmission(query.surface.specularF0, ndv, ndl);
        float3 diffuseReflection = max(query.surface.diffuseAlbedo
                - query.surface.diffuseTransmissionAlbedo, float3(0.0));
        float3 diffuse = diffuseReflection * layerTransmission * SURFACE_INV_PI;
        float3 specular = distribution * geometry * reflectedFresnel
                / max(4.0 * ndv * ndl, 1.0e-6);
        float diffusePdf = ndl * SURFACE_INV_PI * probabilities.y;
        float specularPdf = distribution * ndh / max(4.0 * vdh, 1.0e-6)
                * probabilities.x;
        result.value = diffuse + specular;
        result.diffuseValue = diffuse;
        result.specularValue = specular;
        result.diffusePdf = diffusePdf;
        result.specularPdf = specularPdf;
        result.pdf = diffusePdf + specularPdf;
        result.eventFlags = EVENT_DIFFUSE | EVENT_GLOSSY | EVENT_REFLECTION;
        return result;
    }

    float backNdl = -ndl;
    if (backNdl <= 0.0) return result;
    float3 layerTransmission = surfaceLayerTransmission(query.surface.specularF0, ndv, backNdl);
    float3 diffuse = query.surface.diffuseTransmissionAlbedo
            * layerTransmission * SURFACE_INV_PI;
    float diffusePdf = backNdl * SURFACE_INV_PI * probabilities.z;

    // Mirror the far-side direction into the view hemisphere. A GGX reflection around the resulting
    // half vector becomes an un-deflected straight-through event when the microfacet normal is n.
    float3 mirroredIncoming = reflect(query.incomingDirection, n);
    float3 halfVector = normalize(mirroredIncoming + query.outgoingDirection);
    float ndh = max(0.0, dot(n, halfVector));
    float vdh = max(0.0, dot(query.outgoingDirection, halfVector));
    float distribution = surfaceGgxD(ndh, alpha);
    float geometry = surfaceGgxG1(ndv, alpha) * surfaceGgxG1(backNdl, alpha);
    float3 fresnel = surfaceFresnelSchlick(vdh, query.surface.specularF0);
    float3 sheetReflection;
    float3 sheetTransmission;
    thinSheetFactors(fresnel, query.surface.thinTransmissionColor,
            sheetReflection, sheetTransmission);
    float thinWeight = clamp(query.surface.thinTransmissionWeight, 0.0, 1.0);
    float3 glossyTransmission = distribution * geometry * sheetTransmission * thinWeight
            / max(4.0 * ndv * backNdl, 1.0e-6);
    float glossyPdf = distribution * ndh / max(4.0 * vdh, 1.0e-6) * probabilities.w;

    result.value = diffuse + glossyTransmission;
    result.diffuseValue = diffuse;
    result.specularValue = glossyTransmission;
    result.diffusePdf = diffusePdf;
    result.specularPdf = glossyPdf;
    result.pdf = diffusePdf + glossyPdf;
    result.eventFlags = EVENT_DIFFUSE | EVENT_GLOSSY | EVENT_TRANSMISSION;
    return result;
}

public BsdfSample sampleBsdf(BsdfSampleQuery query) {
    BsdfSample result;
    result.incomingDirection = float3(0.0);
    result.throughputWeight = float3(0.0);
    result.pdf = 0.0;
    result.eventFlags = EVENT_NONE;

    float3 n = query.surface.shadingNormal;
    float ndv = dot(n, query.outgoingDirection);
    if (ndv <= 0.0) return result;

    float4 probabilities = surfaceLobeProbabilities(query.surface, ndv);
    float selector = query.random.x;
    float diffuseReflectionEnd = probabilities.x + probabilities.y;
    float diffuseTransmissionEnd = diffuseReflectionEnd + probabilities.z;
    bool sampleReflectionGlossy = selector < probabilities.x;
    bool sampleDiffuseReflection = !sampleReflectionGlossy && selector < diffuseReflectionEnd;
    bool sampleDiffuseTransmission = selector >= diffuseReflectionEnd
            && selector < diffuseTransmissionEnd;
    bool sampleThinTransmission = selector >= diffuseTransmissionEnd;

    if (sampleReflectionGlossy || sampleThinTransmission) {
        float alpha = query.surface.alphaRoughness;
        float phi = 2.0 * SURFACE_PI * query.random.y;
        float tanTheta2 = alpha * alpha * query.random.z
                / max(1.0 - query.random.z, 1.0e-6);
        float cosTheta = rsqrt(1.0 + tanTheta2);
        float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
        float3 halfVector = surfaceLocalDirection(n, phi, sinTheta, cosTheta);
        float3 reflected = reflect(-query.outgoingDirection, halfVector);
        result.incomingDirection = sampleThinTransmission ? reflect(reflected, n) : reflected;
    } else {
        float phi = 2.0 * SURFACE_PI * query.random.y;
        float sinTheta = sqrt(query.random.z);
        float cosTheta = sqrt(max(0.0, 1.0 - query.random.z));
        result.incomingDirection = surfaceLocalDirection(
                sampleDiffuseTransmission ? -n : n, phi, sinTheta, cosTheta);
    }

    float sampledNdl = dot(n, result.incomingDirection);
    bool transmitted = sampleDiffuseTransmission || sampleThinTransmission;
    if (transmitted ? sampledNdl >= 0.0 : sampledNdl <= 0.0) return result;
    BsdfQuery evaluationQuery;
    evaluationQuery.surface = query.surface;
    evaluationQuery.outgoingDirection = query.outgoingDirection;
    evaluationQuery.incomingDirection = result.incomingDirection;
    BsdfEvaluation evaluation = evaluateBsdf(evaluationQuery);
    if (sampleReflectionGlossy) result.eventFlags = EVENT_GLOSSY | EVENT_REFLECTION;
    if (sampleDiffuseReflection) result.eventFlags = EVENT_DIFFUSE | EVENT_REFLECTION;
    if (sampleDiffuseTransmission) result.eventFlags = EVENT_DIFFUSE | EVENT_TRANSMISSION;
    if (sampleThinTransmission) result.eventFlags = EVENT_GLOSSY | EVENT_TRANSMISSION;
    bool selectedDiffuse = (result.eventFlags & EVENT_DIFFUSE) != 0u;
    float3 selectedValue = selectedDiffuse ? evaluation.diffuseValue : evaluation.specularValue;
    result.pdf = selectedDiffuse ? evaluation.diffusePdf : evaluation.specularPdf;
    result.throughputWeight = selectedValue * abs(sampledNdl) / max(result.pdf, 1.0e-7);
    return result;
}
