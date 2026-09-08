// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

// Color-space definitions are shared by renderer code and material slot contracts.
__exported import caustica_color;
import caustica_types;

// Device pointers use the same std430 offsets as reflected Java serializers. Natural layout
// can change vector alignment and requires the scalarBlockLayout device feature.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;
public typealias DevicePtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;

// GeometryIndex-addressed retained hit state. The TLAS instance custom index is the placement's base.
// The explicit float4 transform rows keep the std430 record aligned. Motion addresses refer to the
// source-owned streams retained by the scene snapshot for this publication.
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
    public uint previousPositionStride;
    public uint64_t previousPositionAddress;
    public uint64_t indexAddress;
    public uint firstIndex;
    public uint reservedMotion;
};

public static const uint RETAINED_GEOMETRY_HAS_VOLUME = 2u;
public static const uint RETAINED_GEOMETRY_STOCHASTIC = 8u;

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

// `globalDistributionAddress` holds one uint2 per light plus a trailing summary entry at index
// `lightCount`: `.x` is the light's sampling proxy count, `.y` its base offset into the proxy array,
// and the summary entry's `.y` is the total proxy count. Proxy counts ARE the global pdf numerator,
// so quantization and clamping stay consistent between sampling and pdf evaluation by construction.
public struct NeeAtState {
    public uint64_t lightTableAddress;
    public uint64_t globalDistributionAddress;
    public uint64_t localDistributionAddress;
    public uint64_t lightFeedbackAddress;
    public uint64_t pixelFeedbackAddress;
    public uint64_t proxyIndexAddress;
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
    // Sum of every light's physical sampling power, summed on the host in double precision so the
    // GPU never needs a reduction to normalize the power-based prior.
    public float powerTotal;
    // Fraction of NEE candidates drawn from the screen-space tile distribution rather than the
    // global one. 0 disables local sampling; at least one global candidate is always kept.
    public float localToGlobalRatio;
};

public static const uint NEE_AT_ENVIRONMENT_EMITTERS_SAMPLED = 2u;
public static const uint NEE_AT_LOCAL_HISTORY_VALID = 1u;
// Average sampling proxies per light. Sets the quantization floor of the global distribution: a
// light's pdf resolution is 1/(lightCount * this). Mirrored by RtNeeAtBackend.PROXY_RATIO, which
// sizes the proxy buffer.
public static const uint NEE_AT_PROXY_RATIO = 12u;
// Weight of last frame's usage feedback against the power-based prior in the global distribution.
// Below 1 always, so newly important lights are still reachable before any feedback exists.
public static const float NEE_AT_GLOBAL_FEEDBACK_WEIGHT = 0.75;

// Single definition shared by the sampler and the baker: tile jitter is derived from it on both
// sides, and the two must agree exactly or sampling and baking address different tiles.
public uint neeAtHash(uint value) {
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    return value ^ (value >> 16u);
}

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
    // bit4 procedural animation.
    public uint     flags;
    public uint     maxBounces;
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
    // Scene-selected environment tuple resolved against the currently published shader program.
    public uint64_t environmentBinding;
    public uint     environmentImplementation;
};
