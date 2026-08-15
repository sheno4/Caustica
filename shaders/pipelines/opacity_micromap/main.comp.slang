import world_common;

static const uint PAGE_CAPACITY = 64u;
[[vk::binding(0, 0)]] Texture2D<float4> temporalAlphaPages[PAGE_CAPACITY];
[[vk::binding(1, 0)]] Texture2D<float4> staticAlphaPages[PAGE_CAPACITY];

struct OpacityMicromapPush {
    uint64_t indexAddress;
    uint64_t textureCoordinateAddress;
    uint64_t primitiveAddress;
    uint64_t materialTableAddress;
    uint64_t surfaceTableAddress;
    uint64_t dataAddress;
    uint64_t triangleAddress;
    uint maskedTriangleBase;
    uint triangleCount;
    uint geometryFlags;
    uint subdivisionLevel;
    uint dataStride;
};

[[vk::push_constant]] OpacityMicromapPush pc;

static const uint OMM_TRANSPARENT = 0u;
static const uint OMM_OPAQUE = 1u;
static const uint OMM_UNKNOWN = 3u;
static const uint OMM_FORMAT_4_STATE = 2u;
static const uint ALPHA_SOURCE_STATIC_PAGE = 1u;
static const uint ALPHA_SOURCE_ANIMATED_RANGE = 2u;

uint barycentricsToOmmIndex(float u, float v, uint level) {
    u = clamp(u, 0.0, 1.0);
    v = clamp(v, 0.0, 1.0);
    int dim = 1 << level;
    float fu = u * dim;
    float fv = v * dim;
    int iu = int(fu);
    int iv = int(fv);
    float uf = fu - iu;
    float vf = fv - iv;
    if (iu >= dim) iu = dim - 1;
    if (iv >= dim) iv = dim - 1;
    int iuv = iu + iv;
    if (iuv >= dim) iu -= iuv - dim + 1;
    int iw = ~(iu + iv);
    if (uf + vf >= 1.0 && iuv < dim - 1) --iw;
    int b0 = ~(iu ^ iw);
    b0 &= dim - 1;
    int t = (iu ^ iv) & b0;
    int f = t;
    f ^= f >> 1;
    f ^= f >> 2;
    f ^= f >> 4;
    f ^= f >> 8;
    int b1 = ((f ^ iu) & ~b0) | t;
    b0 = (b0 | (b0 << 8)) & 0x00ff00ff;
    b0 = (b0 | (b0 << 4)) & 0x0f0f0f0f;
    b0 = (b0 | (b0 << 2)) & 0x33333333;
    b0 = (b0 | (b0 << 1)) & 0x55555555;
    b1 = (b1 | (b1 << 8)) & 0x00ff00ff;
    b1 = (b1 | (b1 << 4)) & 0x0f0f0f0f;
    b1 = (b1 | (b1 << 2)) & 0x33333333;
    b1 = (b1 | (b1 << 1)) & 0x55555555;
    return uint(b0 | (b1 << 1));
}

float2 triangleUv(float2 a, float2 b, float2 c, float ub, float vc) {
    return a + (b - a) * ub + (c - a) * vc;
}

uint classifyFootprint(MaterialBinding binding, SurfaceMaterial surface,
                       float2 uv0, float2 uv1, float2 uv2) {
    if (bindingCoverage(binding) != COVERAGE_CUTOUT
            || bindingBaseColorTextureIndex(binding) != 0u
            || (bindingFlags(binding) & (BINDING_TRANSMISSIVE | BINDING_TEXTURELESS)) != 0u) {
        return OMM_UNKNOWN;
    }
    uint alphaSource = surface.alphaFlags & 3u;
    if (alphaSource == 0u) return OMM_UNKNOWN;
    if (!all(isfinite(uv0)) || !all(isfinite(uv1)) || !all(isfinite(uv2))) return OMM_UNKNOWN;
    float2 minUv = min(uv0, min(uv1, uv2));
    float2 maxUv = max(uv0, max(uv1, uv2));
    float2 repetition = floor(minUv);
    if (any(floor(maxUv - float2(1.0e-7)) != repetition)) return OMM_UNKNOWN;
    minUv -= repetition;
    maxUv -= repetition;
    float2 localMin = (minUv - surface.baseColorUv.xy) * surface.baseColorUv.zw;
    float2 localMax = (maxUv - surface.baseColorUv.xy) * surface.baseColorUv.zw;
    if (!all(isfinite(localMin)) || !all(isfinite(localMax))
            || any(localMin < float2(0.0)) || any(localMax > float2(1.0))) return OMM_UNKNOWN;

    uint page = surface.page & 0xFFFFFFu;
    if (page >= PAGE_CAPACITY) return OMM_UNKNOWN;
    float minAlpha = float(surface.packedAlphaRange & 255u) * (1.0 / 255.0);
    float maxAlpha = float((surface.packedAlphaRange >> 8u) & 255u) * (1.0 / 255.0);
    float cutoff = bindingCutoff(binding);
    if (minAlpha >= cutoff) return OMM_OPAQUE;
    if (maxAlpha < cutoff) return OMM_TRANSPARENT;
    if (alphaSource == ALPHA_SOURCE_STATIC_PAGE) {
        uint page = surface.page & 0xFFFFFFu;
        if (page >= PAGE_CAPACITY) return OMM_UNKNOWN;
        uint pageWidth, pageHeight;
        staticAlphaPages[NonUniformResourceIndex(page)].GetDimensions(pageWidth, pageHeight);
        float2 extent = surface.materialUv.zw * float2(pageWidth, pageHeight);
        int2 materialSize = max(int2(round(extent)), int2(1));
        int2 origin = int2(round(surface.materialUv.xy * float2(pageWidth, pageHeight)));
        const float eps = 1.0e-3;
        int2 first = clamp(int2(floor(localMin * materialSize + eps)), int2(0), materialSize);
        int2 last = clamp(int2(ceil(localMax * materialSize - eps)), int2(0), materialSize);
        last = max(last, min(first + 1, materialSize));
        if (any(first >= last)) return OMM_UNKNOWN;
        bool opaque = true;
        bool transparent = true;
        for (int y = first.y; y < last.y; ++y) {
            for (int x = first.x; x < last.x; ++x) {
                float alpha = staticAlphaPages[NonUniformResourceIndex(page)]
                        .Load(int3(origin + int2(x, y), 0)).r;
                opaque = opaque && alpha >= cutoff;
                transparent = transparent && alpha < cutoff;
            }
        }
        return opaque ? OMM_OPAQUE : (transparent ? OMM_TRANSPARENT : OMM_UNKNOWN);
    }
    if (alphaSource != ALPHA_SOURCE_ANIMATED_RANGE) return OMM_UNKNOWN;
    uint pageWidth, pageHeight;
    temporalAlphaPages[NonUniformResourceIndex(page)].GetDimensions(pageWidth, pageHeight);
    float2 extent = surface.materialUv.zw * float2(pageWidth, pageHeight);
    int2 materialSize = max(int2(round(extent)), int2(1));
    int2 origin = int2(round(surface.materialUv.xy * float2(pageWidth, pageHeight)));
    const float eps = 1.0e-3;
    int2 first = clamp(int2(floor(localMin * materialSize + eps)), int2(0), materialSize);
    int2 last = clamp(int2(ceil(localMax * materialSize - eps)), int2(0), materialSize);
    last = max(last, min(first + 1, materialSize));
    if (any(first >= last)) return OMM_UNKNOWN;

    bool opaque = true;
    bool transparent = true;
    for (int y = first.y; y < last.y; ++y) {
        for (int x = first.x; x < last.x; ++x) {
            float2 range = temporalAlphaPages[NonUniformResourceIndex(page)].Load(int3(origin + int2(x, y), 0)).rg;
            opaque = opaque && range.x >= cutoff;
            transparent = transparent && range.y < cutoff;
        }
    }
    return opaque ? OMM_OPAQUE : (transparent ? OMM_TRANSPARENT : OMM_UNKNOWN);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    uint localTriangle = dispatchId.x;
    if (localTriangle >= pc.triangleCount) return;
    uint triangle = pc.maskedTriangleBase + localTriangle;
    ConstPtr<uint> indices = ConstPtr<uint>(pc.indexAddress);
    ConstPtr<float2> uvs = ConstPtr<float2>(pc.textureCoordinateAddress);
    uint3 vertex = uint3(indices[triangle * 3u], indices[triangle * 3u + 1u], indices[triangle * 3u + 2u]);
    float2 a, b, c;
    if ((pc.geometryFlags & GEOMETRY_INDEXED_TEXTURE_COORDINATES) != 0u) {
        a = uvs[vertex.x]; b = uvs[vertex.y]; c = uvs[vertex.z];
    } else {
        a = uvs[triangle * 3u]; b = uvs[triangle * 3u + 1u]; c = uvs[triangle * 3u + 2u];
    }
    Prim primitive = ConstPtr<Prim>(pc.primitiveAddress)[triangle];
    MaterialBinding binding = ConstPtr<MaterialBinding>(pc.materialTableAddress)[primitive.materialId];
    SurfaceMaterial surface = ConstPtr<SurfaceMaterial>(pc.surfaceTableAddress)[binding.surface];

    uint words[16];
    [unroll] for (uint word = 0u; word < 16u; ++word) words[word] = 0xFFFFFFFFu;
    uint grid = 1u << pc.subdivisionLevel;
    float inverseGrid = 1.0 / float(grid);
    for (uint i = 0u; i < grid; ++i) {
        for (uint j = 0u; i + j < grid; ++j) {
            float2 uv0 = triangleUv(a, b, c, i * inverseGrid, j * inverseGrid);
            float2 uv1 = triangleUv(a, b, c, (i + 1u) * inverseGrid, j * inverseGrid);
            float2 uv2 = triangleUv(a, b, c, i * inverseGrid, (j + 1u) * inverseGrid);
            uint state = classifyFootprint(binding, surface, uv0, uv1, uv2);
            uint micro = barycentricsToOmmIndex((i + 1.0 / 3.0) * inverseGrid,
                    (j + 1.0 / 3.0) * inverseGrid, pc.subdivisionLevel);
            uint shift = (micro & 15u) * 2u;
            words[micro >> 4u] = (words[micro >> 4u] & ~(3u << shift)) | (state << shift);
            if (i + j < grid - 1u) {
                uv0 = uv1;
                uv1 = triangleUv(a, b, c, (i + 1u) * inverseGrid, (j + 1u) * inverseGrid);
                uv2 = triangleUv(a, b, c, i * inverseGrid, (j + 1u) * inverseGrid);
                state = classifyFootprint(binding, surface, uv0, uv1, uv2);
                micro = barycentricsToOmmIndex((i + 2.0 / 3.0) * inverseGrid,
                        (j + 2.0 / 3.0) * inverseGrid, pc.subdivisionLevel);
                shift = (micro & 15u) * 2u;
                words[micro >> 4u] = (words[micro >> 4u] & ~(3u << shift)) | (state << shift);
            }
        }
    }
    DevicePtr<uint> output = DevicePtr<uint>(pc.dataAddress + uint64_t(localTriangle * pc.dataStride));
    uint wordCount = pc.dataStride / 4u;
    for (uint word = 0u; word < wordCount; ++word) output[word] = words[word];

    DevicePtr<uint> descriptor = DevicePtr<uint>(pc.triangleAddress + uint64_t(localTriangle * 8u));
    descriptor[0] = localTriangle * pc.dataStride;
    descriptor[1] = (OMM_FORMAT_4_STATE << 16u) | pc.subdivisionLevel;
}
