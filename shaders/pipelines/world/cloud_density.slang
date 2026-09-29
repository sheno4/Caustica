// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Alpha-Piscium by Luna5ama: Cumulus.glsl and Hash.glsl.
// See licenses/alpha-piscium and THIRD_PARTY_NOTICES.md.
module cloud_density;
import world_common;
import cloud_noise;

static const float _LOW_BASE_FREQ = exp2(0.0);
static const float _LOW_CURL_FREQ = exp2(0.0);
static const float _LOW_BILLOWY_FREQ = exp2(0.0 - 1.0);
static const float _LOW_BILLOWY_CURL_STR = exp2(0.0 - 2.0);
static const float _HIGH_BILLOWY_FREQ = exp2(0.0);
static const float _HIGH_BILLOWY_CURL_STR = exp2(0.0 - 1.0);
static const float _LOW_WISPS_FREQ = exp2(0.0);
static const float _LOW_WISPS_CURL_STR = exp2(0.0 - 2.0);

float _clouds_cu_heightCurveWisp(float4 xs) {

    const float a0 = -1.755622;
    const float4 as = float4(3.6801126, -163.09651, 320.59657, -163.29147);
    const float a5 = 0.01273534;
    return exp2(dot(as, xs) + a0) + a5;
}

float _clouds_cu_heightCurveBillowy(float4 xs) {

    const float a0 = -2.8940248;
    const float4 as = float4(20.938597, -83.765418, 114.27168, -53.99707);
    return exp2(dot(as, xs) + a0);
}

float worleyNoise(float2 x, uint seed) {
    uint2 centerCellID = uint2(int2(floor(x)));
    float2 centerOffset = frac(x);

    float f1 = 0.0;
    float f2 = 0.0;
    float m = 1.0;

    for (int ix = -1; ix <= 1; ++ix) {
        for (int iy = -1; iy <= 1; ++iy) {
            int2 idOffset = int2(ix, iy);
            uint2 cellID = centerCellID + uint2(idOffset + 2);

            uint3 hashPos = uint3(cellID, seed);
            float3 hashValF = hash_uintToFloat(hash_33_q3(hashPos));
            float2 cellCenter = hashValF.xy + float2(idOffset);

            float cellDistance = distance(centerOffset, cellCenter);
            float v = pow2(hashValF.z) * saturate(1.0 - cellDistance);

            const float w = 0.35;
            const float wRcp = 1.4285714286; // 1.0 / w * 0.5
            float h = hashValF.z * saturate((m - cellDistance) * wRcp + 0.5);
            float wh = w * h;
            m = lerp(m, cellDistance, h) - mad(wh, -h, wh) / (1.0 + 3.0 * w);

            if (f1 < v) {
                f2 = f1;
                f1 = v;
            } else if (f2 < v) {
                f2 = v;
            }
        }
    }

    return saturate(1.0 - m - f2);
}

float coverageNoise(float2 pos) {
    pos *= _LOW_BASE_FREQ;
    float higherOctave = cloudTexture2D(0u, 1024, pos / 32.0);
    const float freq = 0.35;
    float baseNoise = worleyNoise(pos * freq, 0x1919810u);
    return baseNoise + higherOctave * 0.75;
}

float clouds_cu_baseCoverage(float2 pos) {
    float baseCoverage = coverageNoise(pos);
    float COVERAGE = cloudFrame.cloudLayer.z;
    float COVERAGE_P = pow2(COVERAGE);
    baseCoverage = max(baseCoverage - (1.0 - COVERAGE_P) * 0.8, 0.0);
    return baseCoverage * (1.0 - pow2(1.0 - COVERAGE));
}

float clouds_cu_isotropicMSBoundaryWeight(float3 rayPos, float localHeight, float columnHeight, float3 lightDir) {
    float sampleStep = clamp(0.05 / _LOW_BASE_FREQ, 0.025, 0.2);
    float coverageXP = saturate(clouds_cu_baseCoverage(rayPos.xz + float2(sampleStep, 0.0)));
    float coverageXN = saturate(clouds_cu_baseCoverage(rayPos.xz - float2(sampleStep, 0.0)));
    float coverageZP = saturate(clouds_cu_baseCoverage(rayPos.xz + float2(0.0, sampleStep)));
    float coverageZN = saturate(clouds_cu_baseCoverage(rayPos.xz - float2(0.0, sampleStep)));
    float dHdx = (coverageXP - coverageXN) * cloudFrame.cloudLayer.y / (2.0 * sampleStep);
    float dHdz = (coverageZP - coverageZN) * cloudFrame.cloudLayer.y / (2.0 * sampleStep);
    float3 normal = normalize(float3(-dHdx, 1.0, -dHdz));
    float rawCTop = saturate((dot(normal, lightDir) + 0.5) / 1.5);
    float cTop = lerp(1.0, rawCTop, 0.25);
    float cBottom = 1.0 - exp(-max(localHeight, 0.0) / (0.1 * lerp(1.0, 4.0, columnHeight)));
    return cTop * cBottom;
}

float detailNoiseB(float3 pos, float3 curl) {
    float3 lowFreqPos = pos * _LOW_BILLOWY_FREQ + curl * _LOW_BILLOWY_CURL_STR;
    float lowFreq = cloudTexture3D(1048576u, 128, lowFreqPos, false).x;
    float3 highFreqPos = pos * _HIGH_BILLOWY_FREQ + curl * _HIGH_BILLOWY_CURL_STR;
    float highFreq = cloudTexture3D(3145728u, 128, highFreqPos, false).x;
    return pow2(1.0 - lowFreq) * 0.4 + pow3(1.0 - highFreq) * 0.2;
}

float detailNoiseW(float3 pos) {
    pos *= _LOW_WISPS_FREQ;
    return cloudTexture3D(3145728u, 128, pos, false).x;
}

float3 detailCurlNoise(float3 pos) {
    pos *= 0.5;
    pos *= _LOW_CURL_FREQ;
    return cloudTexture3D(5242880u, 64, pos, true);
}

bool clouds_cu_density(float3 rayPos, float heightFraction, bool detail, out float densityOut, out float densityLodOut, out float coverageOut) {
    float baseCoverage = clouds_cu_baseCoverage(rayPos.xz);
    coverageOut = saturate(baseCoverage);
    densityOut = baseCoverage;
    float COVERAGE = cloudFrame.cloudLayer.z;
    float COVERAGE_SQRT = sqrt(COVERAGE);

    float x1 = heightFraction;
    float x2 = x1 * x1;
    float x3 = x1 * x2;
    float x4 = x1 * x3;
    float4 xs = float4(x1, x2, x3, x4);

    const float CONE_FACTOR = lerp(5.0, 0.1, 0.5);
    const float CONE_TOP_FACTOR = lerp(3.0, 9.0, 0.5);
    const float TOP_CURVE_FACTOR = float(48);
    const float BOTTOM_CURVE_FACTOR = float(128);

    densityOut *= 1.5 - pow(heightFraction, CONE_FACTOR);
    densityOut *= exp2(-(heightFraction) * CONE_TOP_FACTOR);
    densityOut *= saturate(1.0 - exp2(TOP_CURVE_FACTOR * (heightFraction - 1.0)));
    densityOut *= saturate(1.0 - exp2(-BOTTOM_CURVE_FACTOR * heightFraction));
    densityLodOut = densityOut;
    densityLodOut *= 1.0 + heightFraction * 16.0;

    const float CU_BASE_DENSITY_THRESHOLD = 0.02;

    if (densityOut > CU_BASE_DENSITY_THRESHOLD) {

        float3 curlPos = rayPos;
        curlPos.y *= 1.3;
        float3 detailCurl = detailCurlNoise(curlPos);
        detailCurl *= 0.2 + 0.3 * pow2(heightFraction);
        detailCurl *= linearStep(lerp(CU_BASE_DENSITY_THRESHOLD, 1.0, pow5(1.0 - heightFraction)), 0.0, densityOut);

        float detail1Billowy = detailNoiseB(rayPos, detailCurl);

        float bottomDetail = 1.0 - detail1Billowy * smoothstep(0.1, 0.0, heightFraction) * 2.0;
        float hc3 = _clouds_cu_heightCurveBillowy(xs);
        detail1Billowy *= hc3;
        detail1Billowy *= COVERAGE_SQRT;

        densityOut = linearStep(saturate(detail1Billowy), 1.0, densityOut);

        float detail1Wisp = detailNoiseW(rayPos + detailCurl * _LOW_WISPS_CURL_STR);

        detail1Wisp *= COVERAGE_SQRT;
        float hc2 = _clouds_cu_heightCurveWisp(xs);
        detail1Wisp *= hc2;

        densityOut = linearStep(saturate(detail1Wisp), 1.0, densityOut);

        float hardEdgeBlend = linearStep(0.0, 0.3, heightFraction);
        float minDetailDensity = lerp(0.005, 0.02, hardEdgeBlend);
        float edgeDesnityRange = lerp(0.05, 0.01, hardEdgeBlend);
        densityOut *= sqrt(smoothstep(minDetailDensity, minDetailDensity + edgeDesnityRange, densityOut));

        densityOut *= 1.0 + heightFraction * 16.0;

        densityOut *= bottomDetail;

        if (densityOut > 0.0) {
            return true;
        }
    }

    densityOut = 0.0;
    return false;
}

public float cumulusDensity(float3 p) {
    float h = (p.y - cloudFrame.cloudLayer.x) / cloudFrame.cloudLayer.y;
    if (h <= 0.0 || h >= 1.0) return 0.0;
    float density, lod, coverage;
    clouds_cu_density(p, h, true, density, lod, coverage);
    return max(density, 0.0) * 96.0 * cloudFrame.cloudLayer.w;
}
public float cloudBoundary(float3 p, float3 lightDir) {
    return clouds_cu_isotropicMSBoundaryWeight(p, saturate((p.y-cloudFrame.cloudLayer.x)/cloudFrame.cloudLayer.y), saturate(clouds_cu_baseCoverage(p.xz)), lightDir);
}
