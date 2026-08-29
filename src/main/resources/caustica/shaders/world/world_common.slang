// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

// Re-exported: engine code has used these names since before the API existed, and the colour space a
// slot must return is part of the slot contract, so one definition serves both sides.
__exported import caustica_color;
import caustica_types;

// Read-only device pointer with explicit std430 layout. The layout parameter is LOAD-BEARING: a bare
// Ptr<T> gets Slang's "natural" layout (no vec4 16-byte alignment can shift reflected vector fields
// away from the Java writer's offsets, silently reading garbage) and additionally requires the
// scalarBlockLayout device feature. Keep std430 unless RtDeviceBringup explicitly enables that feature;
// this alias is the single layout switch and generated Java serialization stays in lock-step.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;
public typealias DevicePtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;

// GeometryIndex-addressed retained hit state. The TLAS instance custom index is the placement's base.
// The explicit float4 transform rows and emitter sidecar tail keep the std430 record stride stable.
public struct RetainedGeometryRecord {
    public uint surfaceImplementation;
    public uint coverageImplementation;
    public uint volumeImplementation;
    public uint flags;
    public uint64_t surfaceBinding;
    public uint64_t volumeBinding;
    public uint64_t instanceData;
    public float alphaCutoff;
    public uint reserved;
    public float4 currentRow0;
    public float4 currentRow1;
    public float4 currentRow2;
    public float4 previousRow0;
    public float4 previousRow1;
    public float4 previousRow2;
    public uint64_t emitterIndexAddress;
    public uint emitterPrimitiveBase;
    public uint reservedEmitter;
};

public static const uint RETAINED_GEOMETRY_HAS_VOLUME = 2u;

// Scene-relative geometry and physical radiometry for one immutable retained light.
public struct RetainedLightRecord {
    public uint type;
    public uint flags;
    public float rangeMeters;
    public float angularRadius;
    public float4 positionOrDirection;
    public float4 axisU;
    public float4 axisV;
    public float4 radiometry;
};

public struct NeeAtState {
    public uint64_t lightTableAddress;
    public uint64_t globalDistributionAddress;
    public uint64_t localDistributionAddress;
    public uint64_t lightFeedbackAddress;
    public uint64_t pixelFeedbackAddress;
    public uint lightCount;
    public uint priorLightCount;
    public uint extentWidth;
    public uint extentHeight;
    public uint tileCountX;
    public uint tileCountY;
    public uint tileSize;
    public uint localSlotCount;
    public uint candidateCount;
    public uint flags;
    public float metersPerSceneUnit;
    public uint frameIndex;
};

public static const uint NEE_AT_ENVIRONMENT_EMITTERS_SAMPLED = 2u;

public ConstPtr<NeeAtState> neeAtState(uint64_t address) {
    return (ConstPtr<NeeAtState>)address;
}

// Per-frame world data, read through a 64-bit device address. WorldPushData.java is generated from this
// reflected type, including its serializer, byte size, vector/matrix types, and fixed-array capacities.
public struct WorldPush {
    public float4x4 invViewProj;
    public float3   camOffset;
    public uint     frameIndex;
    public float4x4 prevViewProj;
    public float3   camDelta;
    public float2   jitter;
    // bit0 camera medium active, bit4 procedural animation, bits8..15 camera medium implementation.
    public uint     flags;
    public uint     maxBounces;
    // Environment state is deliberately absent: it belongs to the selected sky slot and lives in that
    // pass's own resources, which engine-generic code cannot reach.
    //
    // Linear ACEScg source colour of the medium the eye itself is inside. A
    // hit's own medium comes from its per-primitive tint, never from here.
    public float3   cameraMedium;
    public uint     cameraMediumIorTransmission; // packHalf2(specular IOR, transmission weight)
    public float    time;                   // animation time (s), wrapped
    // World-pinned offset for procedural evaluation: the 3D rebase origin reduced mod a fixed power of
    // two, so (rebased position + this) stays fixed in scene space at usable precision.
    public float3   proceduralDomainOffset;
    public float4x4 curViewProj;      // forward camera-relative view-projection
    public float    previousTime;     // `time` last frame; equal to `time` with no adjacent frame
    // Pre-exposure scalar applied to radiance at the outImage write
    // ONLY, so all light transport above stays in absolute scene units. Keeps stored fp16 values
    // near mid-grey at any scene brightness; the display pass divides it back out, so the two cancel
    // exactly and this cannot change the image. 1.0 disables it.
    public float    preExposure;
};

// Largest finite half. Producers of fp16 HDR storage clamp to this instead of letting the conversion
// round up to +inf and propagate as NaN — world.rgen's rgba16f outImage store most of all, which is the
// one place the whole path integral's absolute radiance meets a half.
public static const float HALF_MAX = 65504.0;

// Canonical per-triangle record (48 B). Geometry producers resolve source-specific attributes before
// writing this representation; the hit shaders do not branch on producer identity.
public struct Prim {
    public float4 normal; // xyz = geometric normal, w = per-primitive emission strength
    public float4 tint;
    public uint materialId;
    public uint flags;
    // baseColorTextureIndex:16 | coverageMode:2 | texturePresent:1 | textureLinear:1.
    public uint aux0;
    // Conservative provider-authored triangle coverage min:8|max:8; bit 16 means absent.
    public uint aux1;
};

// The compiled material record is split by which stage reads it.
//
// MaterialBinding is the only material record either any-hit loads. Its sixteen aligned bytes hold only
// definition-owned state; per-triangle texture and coverage facts live in Prim.aux0.
public struct MaterialBinding {
    // flags:8 | reserved:16 | surfaceImpl:8. Implementation 0 is the built-in surface; invalid indices
    // visibly resolve to the error surface.
    public uint packed0;
    public uint surface;
    public uint reserved;
    public uint packed1;    // coverageCutoff:8, unorm
};

// Coverage: whether the surface is present along this ray. Varies per texel, and it is the only axis
// whose estimator varies by ray type — radiance rays resolve STOCHASTIC with white noise, guide and
// shadow rays need a single-valued answer and take the deterministic cutoff instead.
public static const uint COVERAGE_CUTOUT = 1u;

// Transmittance: the surface is there and passes light through, so a shadow ray filters and continues
// rather than being blocked.
public static const uint BINDING_TRANSMISSIVE = 1u;

public uint primitiveCoverage(uint aux0) { return (aux0 >> 16u) & 3u; }
public bool primitiveBaseTexturePresent(uint aux0) { return (aux0 & (1u << 18u)) != 0u; }

public uint bindingFlags(MaterialBinding binding) { return binding.packed0 & 255u; }
public float bindingCutoff(MaterialBinding binding) { return float(binding.packed1 & 255u) * (1.0 / 255.0); }

// packHalf2x16 equivalent (Slang core module has no GLSL pack builtin; f32tof16 returns the half
// bit-pattern in the low 16 bits).
public uint packHalf2(float2 v) {
    return (f32tof16(v.y) << 16) | (f32tof16(v.x) & 0xFFFFu);
}

public float2 unpackHalf2(uint p) {
    return float2(f16tof32(p & 0xFFFFu), f16tof32(p >> 16));
}

// Octahedral 16:16 unit-vector packing for compact current/previous guide normals.
public uint packNormalOct(float3 n) {
    float3 v = n / max(abs(n.x) + abs(n.y) + abs(n.z), 1.0e-8);
    float2 p = v.z >= 0.0 ? v.xy
            : (1.0 - abs(float2(v.y, v.x))) * float2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);
    uint2 q = uint2(round(clamp(p * 0.5 + 0.5, 0.0, 1.0) * 65535.0));
    return q.x | (q.y << 16);
}

public float3 unpackNormalOct(uint packed) {
    float2 p = float2(uint2(packed & 0xFFFFu, packed >> 16)) * (1.0 / 65535.0) * 2.0 - 1.0;
    float3 v = float3(p, 1.0 - abs(p.x) - abs(p.y));
    float fold = max(-v.z, 0.0);
    v.x += v.x >= 0.0 ? -fold : fold;
    v.y += v.y >= 0.0 ? -fold : fold;
    return normalize(v);
}
