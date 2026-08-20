import world_common;

struct OpacityMicromapPush {
    uint64_t primitiveAddress;
    uint64_t materialTableAddress;
    uint64_t dataAddress;
    uint64_t triangleAddress;
    uint maskedTriangleBase;
    uint triangleCount;
    uint dataStride;
};

[[vk::push_constant]] OpacityMicromapPush pc;

static const uint OMM_TRANSPARENT = 0u;
static const uint OMM_OPAQUE = 1u;
static const uint OMM_UNKNOWN = 3u;
static const uint OMM_FORMAT_4_STATE = 2u;
static const uint OMM_RANGE_ABSENT = 0x10000u;

uint classifyTriangle(Prim primitive, MaterialBinding binding) {
    if (primitiveCoverage(primitive.aux0) != COVERAGE_CUTOUT
            || !primitiveBaseTexturePresent(primitive.aux0)
            || (bindingFlags(binding) & BINDING_TRANSMISSIVE) != 0u) {
        return OMM_UNKNOWN;
    }
    uint range = primitive.aux1;
    if ((range & OMM_RANGE_ABSENT) != 0u) return OMM_UNKNOWN;
    float minimum = float(range & 255u) * (1.0 / 255.0);
    float maximum = float((range >> 8u) & 255u) * (1.0 / 255.0);
    float cutoff = bindingCutoff(binding);
    if (minimum >= cutoff) return OMM_OPAQUE;
    if (maximum < cutoff) return OMM_TRANSPARENT;
    return OMM_UNKNOWN;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    uint localTriangle = dispatchId.x;
    if (localTriangle >= pc.triangleCount) return;
    uint triangle = pc.maskedTriangleBase + localTriangle;
    Prim primitive = ConstPtr<Prim>(pc.primitiveAddress)[triangle];
    MaterialBinding binding = ConstPtr<MaterialBinding>(pc.materialTableAddress)[primitive.materialId];
    uint state = classifyTriangle(primitive, binding);

    DevicePtr<uint> output = DevicePtr<uint>(pc.dataAddress + uint64_t(localTriangle * pc.dataStride));
    uint wordCount = pc.dataStride / 4u;
    if (wordCount > 0u) output[0] = 0xFFFFFFFCu | state;
    for (uint word = 1u; word < wordCount; ++word) output[word] = 0xFFFFFFFFu;

    DevicePtr<uint> descriptor = DevicePtr<uint>(pc.triangleAddress + uint64_t(localTriangle * 8u));
    descriptor[0] = localTriangle * pc.dataStride;
    descriptor[1] = OMM_FORMAT_4_STATE << 16u;
}
