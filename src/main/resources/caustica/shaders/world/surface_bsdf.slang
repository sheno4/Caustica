// The engine's one OpenPBR BSDF: the authored description resolved into a closure, that closure
// evaluated and sampled, and the closure rebuilt from the payload between stages.
//
// This lives in the engine and not behind the surface slot on purpose. Value and pdf must agree or every
// lighting backend that targets them — RIS, ReSTIR, MIS — biases silently, and an invariant that cannot
// be violated is worth more than one that is merely required. A slot supplies parameters; this turns
// them into light transport.
module surface_bsdf;

import world_common;
import world_core;
import math;
import caustica_types;

// Zero reflectance means there is no interface, so no share of the samples goes to a lobe that would
// evaluate to nothing; every other surface keeps a useful floor.
float specularProbability(SurfaceClosure surface) {
    float f0 = max(surface.specularF0.x, max(surface.specularF0.y, surface.specularF0.z));
    return f0 <= 0.0 ? 0.0 : clamp(f0, 0.05, 0.95);
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
 * The authored description resolved into the quantities the lobes evaluate with. The one conversion that
 * matters is OpenPBR r -> GGX alpha; the floor is the smoothest lobe the sampler resolves without
 * collapsing to a delta the estimator has no event for.
 */
public SurfaceClosure surfaceClosure(MaterialInput material) {
    float roughness = clamp(material.specularRoughness, 0.0, 1.0);
    SurfaceClosure surface;
    surface.shadingNormal = normalize(material.geometryNormal);
    surface.diffuseAlbedo = material.baseColor * (1.0 - material.baseMetalness);
    surface.specularF0 = clamp(openPbrSpecularF0(material), float3(0.0), float3(1.0));
    surface.alphaRoughness = max(roughness * roughness, 4.0e-4);
    // Only a thin wall transmits diffusely; a volume's transmission is refractive, owned by the engine's
    // dielectric transport, and never reaches a closure at all.
    surface.transmission = material.geometryThinWalled
            ? clamp(material.transmissionWeight, 0.0, 1.0) : 0.0;
    return surface;
}

/**
 * Rebuilds the closure closest_hit already computed for this hit. Its fields are exactly what the payload
 * carries, so this is a struct literal rather than a re-evaluation of the material. The one conversion is
 * perceptual roughness back to the GGX alpha the closure is defined in.
 */
public SurfaceClosure payloadSurfaceClosure(float3 shadingNormal) {
    SurfaceClosure surface;
    surface.shadingNormal = shadingNormal;
    surface.diffuseAlbedo = payloadAlbedo();
    surface.specularF0 = payload.f0;
    surface.alphaRoughness = payloadAlphaRoughness();
    surface.transmission = payloadTransmission();
    return surface;
}

// One evaluation covering both hemispheres. The shading normal points toward the viewer, so an incoming
// direction on the far side is a diffuse transmission through the thin wall and one on the near side is
// ordinary reflection; the two are mutually exclusive for a given direction, which is why a single
// shadow ray serves either.
public BsdfEvaluation evaluateBsdf(BsdfQuery query) {
    BsdfEvaluation result;
    result.value = float3(0.0);
    result.pdf = 0.0;
    result.eventFlags = EVENT_NONE;

    float3 n = query.surface.shadingNormal;
    float ndv = dot(n, query.outgoingDirection);
    float ndl = dot(n, query.incomingDirection);
    if (ndv <= 0.0) return result;

    float transmission = query.surface.transmission;
    float specularSelection = specularProbability(query.surface);
    if (ndl <= 0.0) {
        if (transmission <= 0.0) return result;
        float backNdl = -ndl;
        result.value = query.surface.diffuseAlbedo * transmission * INV_PI;
        result.pdf = backNdl * INV_PI * (1.0 - specularSelection) * transmission;
        result.eventFlags = EVENT_DIFFUSE | EVENT_TRANSMISSION;
        return result;
    }

    float3 halfVector = normalize(query.incomingDirection + query.outgoingDirection);
    float ndh = max(0.0, dot(n, halfVector));
    float vdh = max(0.0, dot(query.outgoingDirection, halfVector));
    float alpha = query.surface.alphaRoughness;
    float distribution = ggxD(ndh, alpha);
    float geometry = ggxG1(ndv, alpha) * ggxG1(ndl, alpha);
    float3 fresnel = fresnelSchlick(vdh, query.surface.specularF0);
    float3 diffuse = query.surface.diffuseAlbedo * (1.0 - transmission) * INV_PI;
    float3 specular = distribution * geometry * fresnel / max(4.0 * ndv * ndl, 1.0e-6);
    float diffusePdf = ndl * INV_PI * (1.0 - specularSelection) * (1.0 - transmission);
    float specularPdf = distribution * ndh / max(4.0 * vdh, 1.0e-6) * specularSelection;

    result.value = diffuse + specular;
    result.pdf = diffusePdf + specularPdf;
    result.eventFlags = EVENT_DIFFUSE | EVENT_GLOSSY | EVENT_REFLECTION;
    return result;
}

// A refractive closure never reaches here: the engine routes volume transmission through its own
// dielectric interface, since Fresnel choice, reflect/refract, and the medium a ray continues into are
// engine-owned in full. The transmission this samples is the thin wall's diffuse one.
public BsdfSample sampleBsdf(BsdfSampleQuery query) {
    BsdfSample result;
    result.incomingDirection = float3(0.0);
    result.throughputWeight = float3(0.0);
    result.pdf = 0.0;
    result.eventFlags = EVENT_NONE;

    float3 n = query.surface.shadingNormal;
    float ndv = max(0.0, dot(n, query.outgoingDirection));
    if (ndv <= 0.0) return result;

    // Three lobes, selected with the same weights the evaluation's pdf assumes: specular takes its share
    // first, then the diffuse remainder splits between the two sides by the transmission weight.
    float specularSelection = specularProbability(query.surface);
    float transmissionProbability = (1.0 - specularSelection) * query.surface.transmission;
    float selector = query.random.x;
    bool transmitted = false;
    if (selector < specularSelection) {
        float alpha = query.surface.alphaRoughness;
        float phi = 2.0 * PI * query.random.y;
        float tanTheta2 = alpha * alpha * query.random.z / max(1.0 - query.random.z, 1.0e-6);
        float cosTheta = rsqrt(1.0 + tanTheta2);
        float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
        float3 halfVector = surfaceLocalDirection(n, phi, sinTheta, cosTheta);
        result.incomingDirection = reflect(-query.outgoingDirection, halfVector);
    } else {
        transmitted = selector < specularSelection + transmissionProbability;
        float phi = 2.0 * PI * query.random.y;
        float sinTheta = sqrt(query.random.z);
        float cosTheta = sqrt(max(0.0, 1.0 - query.random.z));
        result.incomingDirection = surfaceLocalDirection(transmitted ? -n : n, phi, sinTheta, cosTheta);
    }

    float sampledNdl = dot(n, result.incomingDirection);
    if (transmitted ? sampledNdl >= 0.0 : sampledNdl <= 0.0) return result;
    BsdfQuery evaluationQuery;
    evaluationQuery.surface = query.surface;
    evaluationQuery.outgoingDirection = query.outgoingDirection;
    evaluationQuery.incomingDirection = result.incomingDirection;
    BsdfEvaluation evaluation = evaluateBsdf(evaluationQuery);
    result.pdf = evaluation.pdf;
    result.throughputWeight = evaluation.value * abs(sampledNdl) / max(evaluation.pdf, 1.0e-7);
    // The lobe that was actually sampled, not the union the evaluation reports: the engine reads
    // EVENT_DIFFUSE to decide whether this bounce may still see the celestial disc.
    result.eventFlags = transmitted ? (EVENT_DIFFUSE | EVENT_TRANSMISSION)
            : (selector < specularSelection ? (EVENT_GLOSSY | EVENT_REFLECTION)
                                            : (EVENT_DIFFUSE | EVENT_REFLECTION));
    return result;
}
