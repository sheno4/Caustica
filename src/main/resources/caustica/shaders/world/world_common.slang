// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

// Re-exported: engine code has used these names since before the API existed, and the colour space a
// slot must return is part of the slot contract, so one definition serves both sides.
__exported import caustica_color;

// Read-only device pointer with explicit std430 layout. The layout parameter is LOAD-BEARING: a bare
// Ptr<T> gets Slang's "natural" layout (no vec4 16-byte alignment can shift reflected vector fields
// away from the Java writer's offsets, silently reading garbage) and additionally requires the
// scalarBlockLayout device feature. Keep std430 unless RtDeviceBringup explicitly enables that feature;
// this alias is the single layout switch and generated Java serialization stays in lock-step.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;
public typealias DevicePtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;

// Push constants. The hot inline lanes avoid dereferencing WorldPush for values that are read in
// hit shaders or used for raygen control flow — every 64-bit device address lives here for exactly that
// reason, rather than behind the worldPushAddr indirection. geometryTableAddr/materialTableAddr
// are read in world.rahit/world.rchit, which never load WorldPush at all; the light-buffer addresses are
// only read in world.rgen, which loads WorldPush anyway, but keeping every Addr in one place means there
// is exactly one struct to check when adding a new device buffer, instead of two.
public struct WorldPushConstants {
    public uint64_t worldPushAddr;
    public uint64_t geometryTableAddr;
    public uint64_t instanceHistoryAddr;
    public uint64_t materialTableAddr;   // MaterialBinding[], indexed by Prim.materialId
    public uint64_t materialSurfaceAddr; // SurfaceMaterial[], indexed by MaterialBinding.surface
    public uint64_t retainedLightAddr;
    public uint64_t retainedLightNodeAddr;
    public uint64_t frameLightAddr;
    public uint64_t frameLightNodeAddr;
    public uint64_t pathQueueAddr;       // packed primary -> indirect continuation records
    public uint frameIndex;
};

// Per-frame world data, read through a 64-bit device address. WorldPushData.java is generated from this
// reflected type, including its serializer, byte size, vector/matrix types, and fixed-array capacities.
public struct WorldPush {
    public float4x4 invViewProj;
    public float3   camOffset;
    public uint     frameIndex;
    public float4x4 prevViewProj;
    public float3   camDelta;
    public uint     spp;
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
    // Retained records are relative to their immutable build origin. xyz shifts them into the current
    // frame origin and w converts one scene unit to metres.
    public float4   retainedLightTransform;
    // x root node, y finite-light count, z linked geometry-emitter count, w reserved.
    public int4     retainedLightInfo;
    public float    previousTime;     // `time` last frame; equal to `time` with no adjacent frame
    // x root node, y finite count, z first distant record, w distant count.
    public int4     frameLightInfo;
    public float    frameMetersPerWorldUnit;
    public uint     risCandidates;    // RIS candidate count M per diffuse vertex (0 = emitter NEE off)
    // Pre-exposure scalar applied to radiance at the outImage write
    // ONLY, so all light transport above stays in absolute scene units. Keeps stored fp16 values
    // near mid-grey at any scene brightness; the display pass divides it back out, so the two cancel
    // exactly and this cannot change the image. 1.0 disables it.
    public float    preExposure;
};

// Source-neutral 64-byte light record. Shape-specific lanes are:
// rectangle: axis0/axis1 are half axes and shapeScalar is the geometric-normal sign;
// point: range is the finite influence radius; spot: axis0 is direction and shapeScalar is cos(outer);
// distant: axis0 points toward the source, shapeScalar is cos(angular radius), radiometry is lux.
// selectionMetric is finite luminous power in lumens or distant illuminance luminance in lux.
public struct GpuLight {
    public float3 pos;
    public uint type; // 0 rectangle, 1 point, 2 spot, 3 distant
    public float3 axis0;
    public float range;
    public float3 axis1;
    public float shapeScalar;
    public float3 radiometry;
    public float selectionMetric;
};

// Source-neutral 48-byte finite-light BVH node. Leaves have lightIndex >= 0; branches have two
// non-negative child indices. Bounds and positions use the same segment-relative scene coordinates.
//
// peakIntensityCandela is the summed peak luminous intensity of the subtree, not its emitted power: it
// is what the proposal divides by squared distance to reach lux, which is the one scale on which a
// rectangle, a point, a spot and a distant light can be compared.
public struct GpuLightNode {
    public float3 boundsMin;
    public int left;
    public float3 boundsMax;
    public int right;
    public float peakIntensityCandela;
    public int lightIndex;
    public uint2 reserved;
};

// Prim.flags bit 0: this emissive primitive has a linked record in the retained light scene.
public static const uint PRIM_EMITTER_IN_LIGHT_SCENE = 1u;

// Largest finite half. Producers of fp16 HDR storage clamp to this instead of letting the conversion
// round up to +inf and propagate as NaN — world.rgen's rgba16f outImage store most of all, which is the
// one place the whole path integral's absolute radiance meets a half.
public static const float HALF_MAX = 65504.0;

// Radiance-ray payload (location 0). Member order and types are a cross-stage ABI.
//
// Three-component quantities are packed into half pairs rather than declared as float3: they cost 6 bytes
// instead of 12, and payload storage is reserved per trace call site, so every byte here is paid twice per
// radiance trace (once for TraceRay, once for Invoke) and is preserved across the SER reorder. hitT stays
// f32 — it reaches 10000 scene units and the hit position is reconstructed from it, where half's
// roughly four-unit spacing at that magnitude would be visible.
public struct Payload {
    // surface0..2 are a hand-packed UNION of the two things a radiance ray can come back with. Reach them
    // only through unpackAlbedo/packAlbedo, unpackNormal/packNormal, unpackSky/packSky — the raw words
    // have no meaning without knowing which case you are in.
    //
    //   hit  — half3 albedo + half3 normal
    //   miss — float3 sky radiance, full fp32
    //
    // The miss case is the entire reason these are hand-packed. Sky is in absolute photometric units and
    // a bright environment emitter can exceed 3.6e5 cd/m², well past half's 65504 ceiling — a
    // half3 could not carry it without first normalising by the frame's exposure, which made a storage
    // format's limitation into a dependency on the exposure controller. A hit has no sky and a miss has
    // neither albedo nor normal, so reinterpreting the same twelve bytes buys the range for free and keeps
    // the payload exactly the size it was.
    //
    // The albedo lane doubles as the shadow ray's accumulated transmittance (see makeShadowPayload).
    public uint   surface0;    // hit: half2(albedo.r, albedo.g) | miss: asuint(sky.r)
    public uint   surface1;    // hit: half2(albedo.b, normal.x) | miss: asuint(sky.g)
    public uint   surface2;    // hit: half2(normal.y, normal.z) | miss: asuint(sky.b)
    public float  hitT;        // >= 0 on hit, < 0 on miss.
    public half3  motionPrev;  // per-vertex world displacement since last frame.
    public half3  f0;          // specular F0.
    // Octahedral 16:16 of the shading normal one frame ago — equal to the normal itself unless the
    // surface animates. Written only for the hit reconstruction anchors on (see PAYLOAD_PRIMARY_RAY),
    // because that is the only hit whose reflection is reprojected through the surface plane. Packed
    // rather than a third half3: it costs one word and is finer than the fp16 the normal itself uses.
    public uint   previousNormal;
    public uint   flags;       // low bits are hit state; high byte is the surface implementation.
    // Metalness has already been resolved into diffuse albedo and F0 at closest-hit, so its former half
    // is the third component of emission without increasing the cross-stage payload.
    public uint   roughEmissionB; // packHalf2x16(perceptual roughness, emission radiance B)
    public uint   emissionRG;     // packHalf2x16(emission radiance R, G)
    // Volume hit: IOR, transmission_weight. Surface hit: thin transmission_weight, reserved zero.
    public uint   iorTransmission;
    // Surface-only closure lanes. Volume hits, misses and shadow rays leave them zero.
    public half3  diffuseTransmissionAlbedo;
    public half3  thinTransmissionColor;
    public uint   rayCone;     // packHalf2x16(width, spread)
};

public static const uint PAYLOAD_SHOW_ENVIRONMENT_EMITTERS = 4u;
// Set by world.rchit on a hit whose primitive carries PRIM_EMITTER_IN_LIGHT_SCENE: this emitter is
// RIS-sampled, so raygen gates its direct-hit emission on diffuse continuation rays (no double count).
public static const uint PAYLOAD_EMITTER_IN_LIST = 128u;
// Set by world.rchit on any dielectric hit: true when the incoming ray travels
// against the prim's outward face normal (entering the volume), false when it exits. Derived from face
// orientation rather than toggled, so a stray or missing face cannot corrupt the medium for the rest of
// the path — each crossing re-derives which side it is on. Bit 2 (PAYLOAD_SHOW_ENVIRONMENT_EMITTERS) is only
// meaningful on a miss and this bit only on a hit, so they never need to be read together, but they get
// distinct bits anyway to keep the two concerns unambiguous.
public static const uint PAYLOAD_DIELECTRIC_ENTERING = 8u;

// Which registered surface implementation this hit resolved through, forwarded from its
// MaterialBinding so the estimator can call the same one's response hook a stage later. The high byte of
// the flags word rather than a payload lane of its own: it is eight bits and the word had them free.
public static const uint PAYLOAD_SURFACE_IMPL_SHIFT = 24u;

// Set by the camera's own first radiance trace: this hit is what the reconstructor anchors on, so
// closest-hit resolves the extra state only reconstruction reads — an animated surface's previous-frame
// normal. Every other ray leaves it clear and pays for none of it.
public static const uint PAYLOAD_PRIMARY_RAY = 512u;

// Set by traceGuide so the radiance any-hit can suppress stochastic alpha. A guide probe answers which
// surface a reflection/refraction points at, so a coin-flip there is a flickering control signal rather
// than a sample that averages out; a deterministic accept lets a translucent surface reach closest-hit as
// a dielectric, which is the crossing the guide loop already walks. world.rchit overwrites flags, but the
// any-hit has already run by then, so the bit never needs to survive the hit.
public static const uint PAYLOAD_GUIDE_RAY = 256u;

// Set by world.rchit whenever the hit routes through the engine's dielectric transport (refracts, pushes
// a medium) rather than the generic PBR surface slot — derived from MaterialBinding.flags &
// BINDING_TRANSMISSIVE, the same bit the any-hits already read, so this is a forward of that fact rather
// than a second encoding of it.
public static const uint PAYLOAD_TRANSMISSIVE = 1u;
// Shadow-only request: record the nearest transmissive crossing whose material implementation matches
// the high-byte implementation carried by the payload. It is never interpreted as radiance hit state.
public static const uint PAYLOAD_TRACK_MEDIUM_BOUNDARY = 2u;

// Canonical per-triangle record (48 B). Geometry producers resolve source-specific attributes before
// writing this representation; the hit shaders do not branch on producer identity.
public struct Prim {
    public float4 normal; // xyz = geometric normal, w = per-primitive emission strength
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// The compiled material record is split by which stage reads it.
//
// MaterialBinding is the only material record either any-hit loads: sixteen aligned bytes answering the
// two questions traversal asks. Coverage — is the surface present along this ray — and transmittance —
// how much light passes where it is present. The two are independent (a stained-glass-textured surface has
// both) and compose sequentially: test coverage, then apply interface behaviour.
//
// A binding is the cheap object. Pairing one SurfaceMaterial with a different base-color texture index or a
// different coverage mode costs sixteen bytes rather than a cloned material, so source render types and
// stochastic submissions use bindings instead of record variants.
public struct MaterialBinding {
    // baseColorTextureIndex:16 | coverageMode:2 | flags:6 | surfaceImpl:8. Index 0 is the host's default
    // base-color texture
    // and implementation 0 is the built-in surface, so a zero-initialised binding resolves the reference
    // textured surface. Other source render types name their own bindless texture index.
    public uint packed0;
    public uint surface;    // index into the SurfaceMaterial table; closest-hit only
    // 8:8:8 ACEScg transmittance through this surface, used when coverage is OPAQUE and no texel is
    // sampled. A compile-time reduction of the surface's transmission colour, not an authored parameter.
    public uint shadowTint;
    public uint packed1;    // coverageCutoff:8, unorm
};

// Coverage: whether the surface is present along this ray. Varies per texel, and it is the only axis
// whose estimator varies by ray type — radiance rays resolve STOCHASTIC with white noise, guide and
// shadow rays need a single-valued answer and take the deterministic cutoff instead.
public static const uint COVERAGE_OPAQUE = 0u;
public static const uint COVERAGE_CUTOUT = 1u;
public static const uint COVERAGE_STOCHASTIC = 2u;

// Transmittance: the surface is there and passes light through, so a shadow ray filters and continues
// rather than being blocked.
public static const uint BINDING_TRANSMISSIVE = 1u;
// The binding owns a uniform linear base colour in SurfaceMaterial.baseColorUv.xyz and never samples a
// base-color texture. Textureless definitions use opaque coverage, so any-hit needs no corresponding path.
public static const uint BINDING_TEXTURELESS = 4u;

public uint bindingBaseColorTextureIndex(MaterialBinding binding) { return binding.packed0 & 0xFFFFu; }
public uint bindingCoverage(MaterialBinding binding) { return (binding.packed0 >> 16) & 3u; }
public uint bindingFlags(MaterialBinding binding) { return (binding.packed0 >> 18) & 63u; }
/** Which registered surface implementation this material's description is routed through. */
public uint bindingSurfaceImpl(MaterialBinding binding) { return binding.packed0 >> 24; }
public float bindingCutoff(MaterialBinding binding) { return float(binding.packed1 & 255u) * (1.0 / 255.0); }

public float3 bindingShadowTint(MaterialBinding binding) {
    return float3(uint3(binding.shadowTint, binding.shadowTint >> 8u, binding.shadowTint >> 16u) & 255u)
            * (1.0 / 255.0);
}

// Closest-hit-only surface parameters, shared by every binding that names them. The uniform half of the
// authored OpenPBR Surface subset: everything that varies per texel lives in the canonical pages `page`
// names instead. Source-format data has already been decoded, so nothing here is source-shaped.
// materialUv maps sprite-local UV into the packed page, while baseColorUv maps the source atlas rectangle
// back to sprite-local UV.
public struct SurfaceMaterial {
    public uint features;     // MATERIAL_FEATURE_* | emission luminance
    // Canonical page index in bits 0..23, and in bits 24..31 the maximum LOD at which this material's
    // rectangle within that page is still valid. The LOD limit describes the page rectangle, not the
    // surface, which is why it travels with the page reference rather than with the parameters below.
    public uint page;
    public uint packedAlphaRange; // mip-zero temporal min:8 | max:8
    // 0 unknown/unproven, 1 immutable static mip-zero alpha page, 2 per-texel temporal min/max page.
    public uint alphaFlags;
    public float4 materialUv; // packed-page uvMin.xy + uvExtent.xy
    public float4 baseColorUv; // source atlas uvMin.xy + inverse uvExtent.xy
    // OpenPBR names, OpenPBR meanings. specularRoughness is PERCEPTUAL r: the GGX alpha every lobe wants
    // is its square, computed where the lobe is evaluated. Nothing in the pipeline stores alpha.
    public float specularRoughness;
    public float baseMetalness;
    public float specularIor;
    public float transmissionWeight;
};

// "Is there a texture to sample": a compiled fetch-skipping optimisation, not an authored concept.
public static const uint MATERIAL_FEATURE_SPEC = 1u;
public static const uint MATERIAL_FEATURE_NORMAL = 2u;
public static const uint MATERIAL_FEATURE_EMISSION_MASK = 4u;
public static const uint MATERIAL_FEATURE_SUBSURFACE_COLOR_BASE = 8u;
public static const uint MATERIAL_FEATURE_EMISSION_COLOR_BASE = 16u;
// Final HDR emitting-surface luminance (look-package baseline or absolute JSON cd/m² override), baked in Java
// at material-compile time (RtMaterialRegistry) and packed here as a 16-bit fraction of the max — every
// emissive material carries a value (0 for non-emissive), not just resource-pack-overridden ones, so the
// full features word's spare 16 bits (8..23) hold the encoded value.
public static const uint MATERIAL_EMISSION_LUMINANCE_SHIFT = 8u;
public static const uint MATERIAL_EMISSION_LUMINANCE_MASK = 65535u;
// Mirrors RtMaterialRegistry.MAX_EMISSION_LUMINANCE — HALF_MAX, the ceiling emission can survive through
// the payload's fp16 emission components and Light.le (R11G11B10). Emission is measured in cd/m².
public static const float MATERIAL_MAX_EMISSION_LUMINANCE = 65504.0;
public static const uint MATERIAL_PAGE_MASK = 0xFFFFFFu;
public static const uint MATERIAL_MAX_LOD_SHIFT = 24u;

public uint surfacePage(SurfaceMaterial mat) { return mat.page & MATERIAL_PAGE_MASK; }
public float surfacePageMaxLod(SurfaceMaterial mat) { return float(mat.page >> MATERIAL_MAX_LOD_SHIFT); }

/** OpenPBR emission_luminance in cd/m²: the emissive baseline this material's emission mask scales. */
public float surfaceEmissionLuminance(SurfaceMaterial mat) {
    return float((mat.features >> MATERIAL_EMISSION_LUMINANCE_SHIFT) & MATERIAL_EMISSION_LUMINANCE_MASK)
            * (MATERIAL_MAX_EMISSION_LUMINANCE / float(MATERIAL_EMISSION_LUMINANCE_MASK));
}

/**
 * Decode the canonical page's specular_ior channel. IOR is stored as its amplitude reflectance
 * (ior - 1) / (ior + 1), which maps the whole dielectric range onto a unorm8 with useful precision where
 * real materials sit. The engine material page compiler owns the matching encode and clamps to the same
 * one-unorm8-step-below-one ceiling before upload.
 */
public float surfaceIorFromPage(float encoded) {
    float amplitude = clamp(encoded, 0.0, 254.0 / 255.0);
    return (1.0 + amplitude) / (1.0 - amplitude);
}

// One record for every TLAS instance. PrimitiveIndex restarts at zero for each BLAS geometry, so hit shaders add
// triangleBase[GeometryIndex()]. Texture-coordinate storage is selected explicitly by flags rather than
// inferred from whether an otherwise optional address happens to be zero.
public static const uint GEOMETRY_TRIANGLE_CORNER_TEXTURE_COORDINATES = 0u;
public static const uint GEOMETRY_INDEXED_TEXTURE_COORDINATES = 1u;
/** Geometry opts into the registered projected surface-modifier chain. */
public static const uint GEOMETRY_RECEIVES_PROJECTED_SURFACE_MODIFIERS = 2u;

public struct GeometryRecord {
    public uint64_t primitiveAddress;
    public uint64_t indexAddress;
    public uint64_t textureCoordinateAddress;
    public uint64_t previousPositionAddress;
    public uint64_t reserved0;
    public uint64_t reserved1;
    public uint triangleBase[3];
    public uint flags;
};

/** Previous object-to-world transform in the current frame's rebased coordinate system. */
public struct InstanceHistory {
    public float4 row0;
    public float4 row1;
    public float4 row2;
};

// packHalf2x16 equivalent (Slang core module has no GLSL pack builtin; f32tof16 returns the half
// bit-pattern in the low 16 bits).
public uint packHalf2(float2 v) {
    return (f32tof16(v.y) << 16) | (f32tof16(v.x) & 0xFFFFu);
}

public float2 unpackHalf2(uint p) {
    return float2(f16tof32(p & 0xFFFFu), f16tof32(p >> 16));
}

// ---- Payload.surface0..2 accessors. Two views of the same twelve bytes; see the struct for why.
//
// The hit view straddles surface1 (albedo.b in its low half, normal.x in its high half), so the two
// setters are read-modify-write on that word. That is a couple of ALU ops on a value already in
// registers, and it buys a payload that did not have to grow. Each setter touches only its own halves,
// so writing one before the other is initialised is safe — nothing here does arithmetic on the half it
// is preserving.
public float3 unpackAlbedo(Payload p) {
    return float3(unpackHalf2(p.surface0), unpackHalf2(p.surface1).x);
}

public void packAlbedo(inout Payload p, float3 albedo) {
    p.surface0 = packHalf2(albedo.xy);
    p.surface1 = (p.surface1 & 0xFFFF0000u) | (f32tof16(albedo.z) & 0xFFFFu);
}

public float3 unpackNormal(Payload p) {
    return float3(unpackHalf2(p.surface1).y, unpackHalf2(p.surface2));
}

public void packNormal(inout Payload p, float3 normal) {
    p.surface1 = (p.surface1 & 0x0000FFFFu) | (f32tof16(normal.x) << 16);
    p.surface2 = packHalf2(normal.yz);
}

// Octahedral 16:16 unit-vector packing, used for Payload.previousNormal. The octahedron maps the sphere
// onto [-1,1]^2 with bounded distortion, so a 16-bit pair resolves finer than the fp16 the payload's
// other normal is stored as, in a quarter of the words.
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

// Miss view: full fp32, so absolute photometric sky needs no pre-exposure round trip through the payload
// and no clamp to keep it finite. The fp16 ceiling still applies at the outImage store in world.rgen,
// where the finite-half clamp guards every radiance source.
public float3 unpackSky(Payload p) {
    return float3(asfloat(p.surface0), asfloat(p.surface1), asfloat(p.surface2));
}

public void packSky(inout Payload p, float3 sky) {
    p.surface0 = asuint(sky.x);
    p.surface1 = asuint(sky.y);
    p.surface2 = asuint(sky.z);
}
