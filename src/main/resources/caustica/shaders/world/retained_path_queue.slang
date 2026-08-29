module retained_path_queue;

import path_queue_types;
import world_common;

public struct RetainedPathState {
    public float3 origin;
    public float3 direction;
    public float3 throughput;
    public float3 absorption;
    public float previousBsdfPdf;
    public float volumeIor;
    public uint seed;
    public uint proposalMode;
    public uint candidateCount;
    public bool volumeActive;
    public bool valid;
};

float2 retainedOctEncode(float3 direction) {
    float3 n = direction / max(abs(direction.x) + abs(direction.y) + abs(direction.z), 1.0e-20);
    float2 signValue = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    float2 oct = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * signValue;
    return oct * 0.5 + 0.5;
}

float3 retainedOctDecode(float2 encoded) {
    float2 oct = encoded * 2.0 - 1.0;
    float3 n = float3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        float2 signValue = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
        n.xy = (1.0 - abs(n.yx)) * signValue;
    }
    return normalize(n);
}

uint retainedPackUnorm16(float2 value) {
    uint2 packed = uint2(round(clamp(value, 0.0, 1.0) * 65535.0));
    return packed.x | (packed.y << 16u);
}

float2 retainedUnpackUnorm16(uint value) {
    return float2(value & 0xffffu, value >> 16u) / 65535.0;
}

public uint retainedPackRgb9e5(float3 value) {
    float3 v = clamp(value, 0.0, 65408.0);
    float maximum = max(v.x, max(v.y, v.z));
    uint exponent = maximum < exp2(-16.0) ? 0u : uint(floor(log2(maximum))) + 16u;
    exponent = min(exponent, 31u);
    float scale = exp2(float(int(exponent) - 24));
    uint maximumMantissa = uint(floor(maximum / scale + 0.5));
    if (maximumMantissa == 512u && exponent < 31u) { exponent++; scale *= 2.0; }
    uint3 mantissa = min(uint3(floor(v / scale + 0.5)), uint3(511u));
    return mantissa.x | (mantissa.y << 9u) | (mantissa.z << 18u) | (exponent << 27u);
}

public float3 retainedUnpackRgb9e5(uint value) {
    float scale = exp2(float(int(value >> 27u) - 24));
    return float3(value & 0x1ffu, (value >> 9u) & 0x1ffu, (value >> 18u) & 0x1ffu) * scale;
}

public PackedPathSegment packRetainedPath(RetainedPathState state, uint nextRecord) {
    PackedPathSegment packed;
    packed.ro = state.origin;
    packed.rd = retainedPackUnorm16(retainedOctEncode(state.direction));
    packed.throughput = retainedPackRgb9e5(state.throughput);
    packed.currentExtinction = retainedPackRgb9e5(state.absorption);
    packed.outerExtinction = asuint(state.previousBsdfPdf);
    packed.mediumIors = packHalf2(float2(state.volumeIor, 1.0));
    packed.rayCone = (state.proposalMode & 0xffu) | ((state.candidateCount & 0xffu) << 8u);
    packed.seed = state.seed;
    packed.pathFlags = (state.volumeActive ? 1u : 0u) | (state.valid ? 0x80000000u : 0u);
    packed.nextRecord = nextRecord;
    return packed;
}

public RetainedPathState unpackRetainedPath(PackedPathSegment packed) {
    RetainedPathState state;
    state.origin = packed.ro;
    state.direction = retainedOctDecode(retainedUnpackUnorm16(packed.rd));
    state.throughput = retainedUnpackRgb9e5(packed.throughput);
    state.absorption = retainedUnpackRgb9e5(packed.currentExtinction);
    state.previousBsdfPdf = asfloat(packed.outerExtinction);
    state.volumeIor = unpackHalf2(packed.mediumIors).x;
    state.seed = packed.seed;
    state.proposalMode = packed.rayCone & 0xffu;
    state.candidateCount = (packed.rayCone >> 8u) & 0xffu;
    state.volumeActive = (packed.pathFlags & 1u) != 0u;
    state.valid = (packed.pathFlags & 0x80000000u) != 0u;
    return state;
}
