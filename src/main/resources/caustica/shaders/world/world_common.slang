// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

// Re-exported: engine code has used these names since before the API existed, and the colour space a
// slot must return is part of the slot contract, so one definition serves both sides.
__exported import caustica_color;

// Read-only device pointer with explicit std430 layout. The layout parameter is LOAD-BEARING: a bare
// Ptr<T> gets Slang's "natural" layout (no vec4 16-byte alignment → e.g. WorldPush.sunDir lands at 200
// instead of the Java writer's 208, silently reading garbage) and additionally requires the
// scalarBlockLayout device feature. Keep std430 unless RtDeviceBringup explicitly enables that feature;
// this alias is the single layout switch and generated Java serialization stays in lock-step.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;
public typealias DevicePtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;

// Push constant block. The hot inline lanes avoid dereferencing WorldPush for values that are read in
// hit shaders or used for raygen control flow — every 64-bit device address lives here for exactly that
// reason, rather than behind the worldPushAddr indirection. tableAddr/entityTableAddr/materialTableAddr
// are read in world.rahit/world.rchit, which never load WorldPush at all; the light-buffer addresses are
// only read in world.rgen, which loads WorldPush anyway, but keeping every Addr in one place means there
// is exactly one struct to check when adding a new device buffer, instead of two.
public struct WorldPushConstants {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint64_t materialTableAddr;   // MaterialBinding[], indexed by Prim.materialId
    public uint64_t materialSurfaceAddr; // SurfaceMaterial[], indexed by MaterialBinding.surface
    public uint64_t lightBufAddr;        // RIS emitter-NEE global light buffer (0 = none published)
    public uint64_t lightAliasAddr;      // power-weighted O(1) alias table (0 = uniform fallback)
    public uint64_t lightLocalAliasAddr; // power aliases relative to each section's range
    public uint64_t lightGridCellAddr;   // dense light grid cell headers (0 = global proposals only)
    public uint64_t lightGridSpanAddr;   // packed weighted section spans referenced by cell headers
    public uint64_t pathQueueAddr;       // packed primary -> indirect continuation records
    public uint frameIndex;
};

// Block-breaking overlay entry: xyz = breaking block position (rebased, same space as gl_WorldRay*),
// w = bindless entityAlbedoTex slot of the destroy-stage texture.
public struct BreakEntry {
    public int4 posSlot;
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
    public uint     flags;            // bit0 submerged, bit4 waves
    public uint     maxBounces;
    // Sky state is deliberately absent: it belongs to the selected sky slot and lives in that pass's
    // own buffer (SkyInputs, caustica_sky_atmosphere), which engine-generic code cannot reach.
    public float4   waterParams;      // xyz linear ACEScg camera-biome water tint, w wave time (s)
    public float4   waterAnchor;      // xy world-stable wave-domain anchor, z previous wave time
    public float4x4 curViewProj;      // forward camera-relative view-projection
    public uint     breakCount;
    public BreakEntry breaking[8];    // Java capacity and breakCount serialization are generated from this
    public float4   lightRebase;      // xyz hierarchy-to-current rebase, w inverse total light power
    public float4   lightGridOrigin;     // xyz minimum cell origin in rebased blocks, w cell size
    public int4     lightGridDims;       // xyz dense grid dimensions, w reserved
    public uint     lightCount;       // packed Light records in pc.lightBufAddr
    public uint     risCandidates;    // RIS candidate count M per diffuse vertex (0 = emitter NEE off)
    // Pre-exposure scalar applied to radiance at the outImage write
    // ONLY, so all light transport above stays in absolute scene units. Keeps stored fp16 values
    // near mid-grey at any scene brightness; the display pass divides it back out, so the two cancel
    // exactly and this cannot change the image. 1.0 disables it.
    public float    preExposure;
};

// 32-byte hot area-light record. Linear ACEScg radiance uses packed R11G11B10; the
// grid-relative owner section uses three unsigned 10-bit coordinates; the geometric normal is
// reconstructed from the half axes.
//
// 32 divides the 64-byte cache line, so a record never straddles one. RIS fetches random records M times
// per shading vertex, making this alignment important. float3 forces 16-byte struct alignment and 32 is
// a multiple of it, so this lands with no tail padding.
//
// The rectangle spans 2U x 2V, so its area is derived as 4*|U x V|. lightGeometricNormal already
// needs that cross product, allowing the compiler to share the length with normalization.
public struct Light {
    public float3 pos;      // rebased world centre, f32 (the RIS target divides by distance to it)
    public uint   le;       // bitcast R11G11B10 radiance
    public uint   halfUxy;  // half2(U.x, U.y)
    public uint   halfUzVx; // half2(U.z, V.x)
    public uint   halfVyz;  // half2(V.y, V.z)
    public uint   section;  // 10:10:10 grid-relative section coord + bit 30 normal-flip
};

// Eight-byte Vose alias-table column. PDFs are reconstructed from the selected Light.
public struct LightAlias {
    public uint  aliasIndex;
    public float accept;
};

// Dense light grid cell entry. Empty coordinates have spanCount=0; populated section cells point into
// an immutable list of weighted section light ranges built and published alongside this grid.
public struct LightGridCell {
    public uint spanOffset;
    public uint spanCount;
    public float invWeightSum;
};

// One 16-byte Vose alias column for a cell's section distribution. The two light counts are uint16s.
public struct LightGridSpan {
    public uint firstLight;
    public uint aliasFirstLight;
    public uint packedLightCounts; // low16 self, high16 alias
    public float accept;
};

// TerrainPrim.flags bit 0: this emissive quad is in the light buffer (RtLightCollector membership).
public static const uint TERRAIN_PRIM_IN_LIGHT_BUFFER = 1u;

// Largest finite half. Producers of fp16 HDR storage clamp to this instead of letting the conversion
// round up to +inf and propagate as NaN — world.rgen's rgba16f outImage store most of all, which is the
// one place the whole path integral's absolute radiance meets a half.
public static const float HALF_MAX = 65504.0;

// Radiance-ray payload (location 0). Member order and types are a cross-stage ABI.
//
// Three-component quantities are packed into half pairs rather than declared as float3: they cost 6 bytes
// instead of 12, and payload storage is reserved per trace call site, so every byte here is paid twice per
// radiance trace (once for TraceRay, once for Invoke) and is preserved across the SER reorder. hitT stays
// f32 — it reaches 10000 blocks and the hit position is reconstructed from it, where half's ~4-block
// spacing at that magnitude would be visible.
public struct Payload {
    // surface0..2 are a hand-packed UNION of the two things a radiance ray can come back with. Reach them
    // only through unpackAlbedo/packAlbedo, unpackNormal/packNormal, unpackSky/packSky — the raw words
    // have no meaning without knowing which case you are in.
    //
    //   hit  — half3 albedo + half3 normal
    //   miss — float3 sky radiance, full fp32
    //
    // The miss case is the entire reason these are hand-packed. Sky is in absolute photometric units and
    // the sun disc alone is ~3.6e5 cd/m², well past half's 65504 ceiling — a
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
    public uint   flags;       // bit 0 transmissive, bit 1 water, bit 2 celestial, bit 3 dielectric-entering.
    public uint   roughMetal;  // packHalf2x16(perceptual roughness, metalness)
    public uint   emission;    // packHalf2x16(emission cd/m², unused)
    public uint   iorTransmission; // packHalf2x16(IOR, transmission factor)
    public uint   rayCone;     // packHalf2x16(width, spread)
};

public static const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Set by world.rchit on a terrain hit whose prim carries TERRAIN_PRIM_IN_LIGHT_BUFFER: this emitter is
// RIS-sampled, so raygen gates its direct-hit emission on diffuse continuation rays (no double count).
public static const uint PAYLOAD_EMITTER_IN_LIST = 128u;
// Set by world.rchit on any dielectric hit (water or glass/ice): true when the incoming ray travels
// against the prim's outward face normal (entering the volume), false when it exits. Derived from face
// orientation rather than toggled, so a stray or missing face cannot corrupt the medium for the rest of
// the path — each crossing re-derives which side it is on. Bit 2 (PAYLOAD_SHOW_CELESTIAL) is only
// meaningful on a miss and this bit only on a hit, so they never need to be read together, but they get
// distinct bits anyway to keep the two concerns unambiguous.
public static const uint PAYLOAD_DIELECTRIC_ENTERING = 8u;

// Set by traceGuide so the radiance any-hit can suppress stochastic alpha. A guide probe answers which
// surface a reflection/refraction points at, so a coin-flip there is a flickering control signal rather
// than a sample that averages out; a deterministic accept lets a translucent entity reach closest-hit as
// a dielectric, which is the crossing the guide loop already walks. world.rchit overwrites flags, but the
// any-hit has already run by then, so the bit never needs to survive the hit.
public static const uint PAYLOAD_GUIDE_RAY = 256u;

// Set by world.rchit whenever the hit routes through the engine's dielectric transport (refracts, pushes
// a medium) rather than the generic PBR surface slot — derived from MaterialBinding.flags &
// BINDING_TRANSMISSIVE, the same bit the any-hits already read, so this is a forward of that fact rather
// than a second encoding of it.
public static const uint PAYLOAD_TRANSMISSIVE = 1u;
// Set alongside PAYLOAD_TRANSMISSIVE for water specifically (forwarded from BINDING_RECORD_CROSSING,
// which today is set only for water): an animated wave surface, the caustic term, and biome-tint
// absorption calibrated per block of depth are behaviour nothing else needs. Every other transmissive
// surface — glass, ice, any solid transparent block a pack defines — is this bit unset.
public static const uint PAYLOAD_WATER = 2u;

// Entity per-triangle record (48 B). The final lane mirrors TerrainPrim's integer material metadata.
public struct Prim {
    public float4 normal; // xyz = geometric normal, w = per-primitive emission strength
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Terrain per-triangle record (48 B). The final 16-byte lane contains integer geometry-local metadata;
// shared surface parameters live once in SurfaceMaterial instead of being copied into every triangle.
public struct TerrainPrim {
    public float4 normal;
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
// how much light passes where it is present. The two are independent (a stained-glass-textured entity has
// both) and compose sequentially: test coverage, then apply interface behaviour.
//
// A binding is the cheap object. Pairing one SurfaceMaterial with a different bindless albedo slot or a
// different coverage mode costs sixteen bytes rather than a cloned material, so entity render types and
// stochastic submissions are bindings instead of record variants.
public struct MaterialBinding {
    // albedoSlot:16 | coverageMode:2 | flags:6. Slot 0 is the vanilla block atlas, so a zero-initialised
    // binding samples terrain albedo correctly; entity render types resolve their own slot through
    // RtEntityTextures.
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
// Report the nearest crossing distance in Payload.hitT. For an underwater shading point that is where its
// sun shadow ray leaves the medium, which is where world.rgen evaluates the refraction caustic.
public static const uint BINDING_RECORD_CROSSING = 2u;

public uint bindingAlbedoSlot(MaterialBinding binding) { return binding.packed0 & 0xFFFFu; }
public uint bindingCoverage(MaterialBinding binding) { return (binding.packed0 >> 16) & 3u; }
public uint bindingFlags(MaterialBinding binding) { return (binding.packed0 >> 18) & 63u; }
public float bindingCutoff(MaterialBinding binding) { return float(binding.packed1 & 255u) * (1.0 / 255.0); }

public float3 bindingShadowTint(MaterialBinding binding) {
    return float3(uint3(binding.shadowTint, binding.shadowTint >> 8u, binding.shadowTint >> 16u) & 255u)
            * (1.0 / 255.0);
}

// Closest-hit-only surface parameters, shared by every binding that names them. The uniform half of the
// authored OpenPBR Surface subset: everything that varies per texel lives in the canonical pages `page`
// names instead. Source-format data has already been decoded, so nothing here is LabPBR-shaped.
// materialUv maps sprite-local UV into the packed page, while albedoUv maps the vanilla atlas rectangle
// back to sprite-local UV.
public struct SurfaceMaterial {
    public uint features;     // MATERIAL_FEATURE_* | emission luminance
    // Canonical page index in bits 0..23, and in bits 24..31 the maximum LOD at which this material's
    // rectangle within that page is still valid. The LOD limit describes the page rectangle, not the
    // surface, which is why it travels with the page reference rather than with the parameters below.
    public uint page;
    public float4 materialUv; // packed-page uvMin.xy + uvExtent.xy
    public float4 albedoUv;   // vanilla atlas uvMin.xy + inverse uvExtent.xy
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
// Final HDR emitting-surface luminance (look-package baseline or absolute JSON cd/m² override), baked in Java
// at material-compile time (RtMaterialRegistry) and packed here as a 16-bit fraction of the max — every
// emissive material carries a value (0 for non-emissive), not just resource-pack-overridden ones, so the
// full features word's spare 16 bits (8..23) hold the encoded value.
public static const uint MATERIAL_EMISSION_LUMINANCE_SHIFT = 8u;
public static const uint MATERIAL_EMISSION_LUMINANCE_MASK = 65535u;
// Mirrors RtMaterialRegistry.MAX_EMISSION_LUMINANCE — HALF_MAX, the ceiling emission can survive through
// Payload.emission (half2) and Light.le (R11G11B10). Emission is measured in cd/m².
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
 * real materials sit; RtLabPbr owns the matching encode.
 */
public float surfaceIorFromPage(float encoded) {
    float amplitude = clamp(encoded, 0.0, 254.0 / 255.0);
    return (1.0 + amplitude) / (1.0 - amplitude);
}

// Per-section geometry record: packed buffer addresses + per-class triangle offsets
// (gl_PrimitiveID restarts at 0 per geometry, so hit shaders re-add triBase[geometryIndex]).
public struct Section {
    public uint64_t primAddr;
    public uint64_t uvAddr;
    public uint triBase[3]; // fixed SBT class order: opaque/masked/transmissive
};

// Entity geometry record: packed buffer addresses, motion, and per-geometry triangle bases. Entity BLAS
// geometry order is opaque/masked/transmissive (RtAccel.SBT_CLASSES), the same order terrain uses;
// PrimitiveIndex restarts at zero for each geometry. `reserved` is a single uint, not two: shrunk from
// std430 tail padding when triBase grew from 2 to 3 lanes, keeping the struct at 64 bytes rather than
// padding out to 80 (float4 alignment already lands triBase on a 4-byte boundary with no slack to spare
// beyond one more uint).
public struct EntityGeom {
    public uint64_t primAddr;
    public uint64_t idxAddr;
    public uint64_t uvAddr;
    public uint64_t dispAddr;
    public float4 rigidDisp;
    public uint triBase[3];
    public uint reserved;
};

// TLAS instance-custom-index encoding + terrain geometry classes (shared by rahit/rchit).
public static const uint ENTITY_BIT = 0x800000u;
public static const uint PARTICLE_BIT = 0x400000u; // particles share the entity cutout path
public static const uint IDX_MASK = 0x3FFFFFu;     // low 22 bits = geom-table index

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

