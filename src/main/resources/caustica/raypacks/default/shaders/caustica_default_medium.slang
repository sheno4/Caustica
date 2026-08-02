module caustica_default_medium;

import caustica_ray_pack_api;

static const float PI = 3.14159265359;

float defaultHenyeyGreenstein(float cosine, float anisotropy) {
    float g2 = anisotropy * anisotropy;
    float denominator = max(1.0 + g2 - 2.0 * anisotropy * cosine, 1.0e-6);
    return (1.0 - g2) / (4.0 * PI * denominator * sqrt(denominator));
}

void defaultPhaseBasis(float3 n, out float3 tangent, out float3 bitangent) {
    float3 up = abs(n.z) < 0.999 ? float3(0.0, 0.0, 1.0) : float3(1.0, 0.0, 0.0);
    tangent = normalize(cross(up, n));
    bitangent = cross(n, tangent);
}

public PackMedium defaultCreateMedium(PackMediumInput input) {
    PackMedium medium;
    float density = max(input.density, 0.0);
    if (input.mediumTag == PACK_MEDIUM_WATER) {
        float3 transmittance = clamp(input.tint, float3(1.0e-4), float3(1.0));
        medium.absorption = -log(transmittance) * density
                + float3(0.015, 0.010, 0.008);
        medium.scattering = float3(0.012, 0.020, 0.028) * density;
        medium.anisotropy = 0.8;
        medium.ior = 1.333;
    } else {
        float weather = max(input.frame.rain, input.frame.thunder);
        medium.scattering = float3(0.00008, 0.00010, 0.00014)
                * density * (1.0 + 4.0 * weather);
        medium.absorption = float3(0.00001) * density;
        medium.anisotropy = 0.6;
        medium.ior = 1.0;
    }
    medium.emissionRadiance = float3(0.0);
    return medium;
}

public PackPhaseEvaluation defaultEvaluatePhase(PackPhaseQuery query) {
    PackPhaseEvaluation evaluation;
    float cosine = dot(query.incomingDirection, query.outgoingDirection);
    evaluation.value = defaultHenyeyGreenstein(cosine, query.medium.anisotropy);
    evaluation.pdf = evaluation.value;
    return evaluation;
}

public PackPhaseSample defaultSamplePhase(PackPhaseSampleQuery query) {
    PackPhaseSample sample;
    float g = query.medium.anisotropy;
    float cosine;
    if (abs(g) < 1.0e-3) {
        cosine = 1.0 - 2.0 * query.random.x;
    } else {
        float ratio = (1.0 - g * g) / (1.0 - g + 2.0 * g * query.random.x);
        cosine = (1.0 + g * g - ratio * ratio) / (2.0 * g);
    }
    cosine = clamp(cosine, -1.0, 1.0);
    float sine = sqrt(max(0.0, 1.0 - cosine * cosine));
    float phi = 2.0 * PI * query.random.y;
    float3 tangent;
    float3 bitangent;
    defaultPhaseBasis(query.incomingDirection, tangent, bitangent);
    sample.outgoingDirection = normalize(tangent * (cos(phi) * sine)
            + bitangent * (sin(phi) * sine) + query.incomingDirection * cosine);
    sample.pdf = defaultHenyeyGreenstein(cosine, g);
    sample.throughputWeight = 1.0;
    return sample;
}
