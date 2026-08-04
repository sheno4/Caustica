// Composition-generic closest-hit shader. Geometry decode, texture sampling, LabPBR decode, ray-cone
// LOD, and the block-breaking overlay stay engine-owned; opaque terrain and entity materials are routed
// through the selected surface slot's createSurface implementation.
//
// WorldShaderCompiler specializes this generic entry point with the active composition.
// Water, stained glass, ice, and particles stay on engine-owned paths because dielectric transitions and
// fixed billboard receivers are not surface-slot policy.
import world_common;
import math;
import bindings;
import sky;
import caustica_types;
import caustica_surface;
import caustica_api;
import frame;

void payloadSetPacked(inout Payload payload, uint material, float roughness, float metalness,
        float emission, float sss, float ior, float transmission, uint emissionSource) {
    payload.flags = material | (emissionSource << PAYLOAD_EMISSION_SOURCE_SHIFT);
    payload.roughMetal = packHalf2(float2(roughness, metalness));
    payload.emissionSss = packHalf2(float2(emission, sss));
    payload.iorTransmission = packHalf2(float2(ior, transmission));
}

void payloadSetDielectric(inout Payload payload, uint material, bool entering) {
    if (material != MATERIAL_WATER && material != MATERIAL_DIELECTRIC) return;
    if (entering) payload.flags |= PAYLOAD_DIELECTRIC_ENTERING;
}

uint materialEmissionSource(MaterialHeader header, float emission) {
    if (emission <= 0.0) return EMISSION_SOURCE_NONE;
    if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) return EMISSION_SOURCE_LAB_PBR;
    if ((header.features & MATERIAL_FEATURE_HEURISTIC_EMISSION) != 0u) return EMISSION_SOURCE_HEURISTIC;
    return EMISSION_SOURCE_UNIFORM;
}

static const float NORMAL_AO_STRENGTH = 0.5;

static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
static const float RAY_CONE_MIN_TEXEL_FOOTPRINT = 1.0e-4;
static const float RAY_CONE_MAX_LOD = 12.0;

float2 texSizePx(Sampler2D t) {
    uint w, h;
    t.GetDimensions(w, h);
    return float2(w, h);
}

float rayConeHitWidth(uint rayCone) {
    float2 cone = unpackHalf2(rayCone);
    return max(cone.x + cone.y * max(RayTCurrent(), 0.0), RAY_CONE_MIN_WIDTH);
}

float edgeTexelsPerWorld(float3 p0, float3 p1, float2 uv0, float2 uv1, float2 textureSizePx) {
    float worldLen = length(p1 - p0);
    if (worldLen <= 1.0e-7) {
        return 0.0;
    }
    return length((uv1 - uv0) * textureSizePx) / worldLen;
}

float rayConeTextureLod(uint rayCone, float2 textureSizePx, float3 p0, float3 p1, float3 p2, float2 uv0, float2 uv1, float2 uv2) {
    float texelsPerWorld = max(edgeTexelsPerWorld(p0, p1, uv0, uv1, textureSizePx),
            max(edgeTexelsPerWorld(p1, p2, uv1, uv2, textureSizePx),
                edgeTexelsPerWorld(p2, p0, uv2, uv0, textureSizePx)));
    float footprint = max(rayConeHitWidth(rayCone) * texelsPerWorld, RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

float rayConeUnitUvLod(uint rayCone, float2 textureSizePx) {
    float footprint = max(rayConeHitWidth(rayCone) * max(textureSizePx.x, textureSizePx.y), RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

float3 perturbNormal(float3 n, float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2, float3 vdir, float4 ntex, out float ao) {
    ao = 1.0;
    float2 g1 = t1 - t0;
    float2 g2 = t2 - t0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return n;
    }
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    float3 T = normalize(traw - n * dot(n, traw));
    float3 B = cross(n, T) * (det < 0.0 ? -1.0 : 1.0);
    float2 nxy = ntex.xy * 2.0 - 1.0;
    float nz = sqrt(max(0.0, 1.0 - dot(nxy, nxy)));
    float3 nm = normalize(nxy.x * T + nxy.y * B + nz * n);
    float NoV = dot(nm, vdir);
    if (NoV < 0.02) {
        nm = normalize(nm + vdir * (0.02 - NoV));
    }
    ao = lerp(1.0, ntex.b, NORMAL_AO_STRENGTH);
    return nm;
}

struct Surface {
    float3 normal;
    float3 f0;
    float roughness;
    float metalness;
    float emission;
    float sss;
    float ao;
};

float4 samplePageSurface0(uint page, float2 uv, float lod) {
    return materialSurface0Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}
float4 samplePageSurface1(uint page, float2 uv, float lod) {
    return materialSurface1Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}
float4 samplePageNormalAo(uint page, float2 uv, float lod) {
    return materialNormalAoTex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}

Surface evaluateMaterial(MaterialHeader header, float2 atlasUv, float lod, float3 albedo,
        float3 geometricNormal, float3 p0, float3 p1, float3 p2,
        float2 uv0, float2 uv1, float2 uv2, float3 vdir,
        float fallbackRoughness, float fallbackMetalness, float fallbackEmission, bool allowSss) {
    Surface surface;
    surface.normal = geometricNormal;
    surface.roughness = fallbackRoughness;
    surface.metalness = fallbackMetalness;
    surface.f0 = lerp(float3(0.04, 0.04, 0.04), albedo, fallbackMetalness);
    surface.emission = fallbackEmission;
    surface.sss = 0.0;
    surface.ao = 1.0;

    float2 localUv = clamp((atlasUv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    float2 pageUv = header.materialUv.xy + localUv * header.materialUv.zw;
    float maxMaterialLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    float materialLod = min(lod, maxMaterialLod);
    uint page = header.texturePage;
    if ((header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_HEURISTIC_EMISSION)) != 0u) {
        float4 surface0 = samplePageSurface0(page, pageUv, materialLod);
        if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) {
            float4 surface1 = samplePageSurface1(page, pageUv, materialLod);
            surface.roughness = surface0.r;
            surface.metalness = surface0.g;
            surface.emission = surface0.b;
            surface.sss = allowSss ? surface0.a : 0.0;
            surface.f0 = clamp(bt709ToAcesCg(surface1.rgb), float3(0.0), float3(1.0));
        } else {
            surface.emission = surface0.b * fallbackEmission;
        }
    }
    float strength = float((header.features >> MATERIAL_EMISSION_STRENGTH_SHIFT)
            & MATERIAL_EMISSION_STRENGTH_MASK)
            * (MATERIAL_MAX_EMISSION_STRENGTH / float(MATERIAL_EMISSION_STRENGTH_MASK));
    surface.emission *= strength;
    if ((header.features & MATERIAL_FEATURE_NORMAL) != 0u) {
        float4 normalAo = samplePageNormalAo(page, pageUv, materialLod);
        surface.normal = perturbNormal(geometricNormal, p0, p1, p2, uv0, uv1, uv2,
                vdir, normalAo, surface.ao);
    }
    return surface;
}

float3 applyBreaking(float3 albedo709, uint rayCone, float3 rayOrigin, float3 rayDir, float hitT, float3 n) {
    ConstPtr<WorldPush> pcRef = ConstPtr<WorldPush>(pc.worldPushAddr);
    uint breakCount = pcRef[0].breakCount;
    if (breakCount == 0u) {
        return albedo709;
    }
    float3 hitPos = rayOrigin + rayDir * hitT;
    int3 blockPos = int3(floor(hitPos - n * 0.01));
    for (uint bi = 0u; bi < breakCount; ++bi) {
        int4 ps = pcRef[0].breaking[bi].posSlot;
        if (all(ps.xyz == blockPos)) {
            float3 local = hitPos - float3(blockPos);
            float3 an = abs(n);
            float2 decalUv = (an.x >= an.y && an.x >= an.z) ? local.zy
                           : (an.y >= an.z) ? local.xz
                           : local.xy;
            float decalLod = rayConeUnitUvLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(ps.w)]));
            float3 crack709 = srgbToLinear(
                    entityAlbedoTex[NonUniformResourceIndex(ps.w)].SampleLevel(decalUv, decalLod).rgb);
            return clamp(crack709 * albedo709, 0.0, 1.0);
        }
    }
    return albedo709;
}

// Builds the canonical, engine-decoded material facts handed to createSurface. `finalAlbedo`
// is ACEScg, post block-breaking overlay, but deliberately NOT ao- or metal-multiplied: those two splits
// are the surface slot's job.
// `diffuseAlbedo = baseColor * (1 - metalness) * ao`. Multiplication commutes, so folding ao/breaking in
// either order reproduces the built-in surface result.
MaterialInput buildMaterialInput(Surface surface, float3 finalAlbedo, float interiorIor) {
    MaterialInput material;
    material.baseColor = finalAlbedo;
    material.opacity = 1.0; // opaque bucket only; any-hit already resolved cutout alpha
    material.alphaRoughness = surface.roughness;
    material.metalness = surface.metalness;
    material.specularF0 = surface.f0;
    // The payload's emission channel is still a scalar multiplied against the final albedo at the read
    // site (indirect_core), not an independent colour — see the Payload struct doc. A slot's
    // emissionRadiance is accepted here for API completeness and future use, but is not yet threaded back
    // out; the engine's own LabPBR/heuristic emission stays authoritative until the payload ABI carries a
    // real emission colour. This is the same representational gap as demodulationAlbedo.
    material.emissionRadiance = finalAlbedo * max(surface.emission, 0.0);
    material.transmission = 0.0; // opaque bucket only; dielectrics never reach this path
    material.interiorIor = interiorIor;
    material.subsurface = surface.sss;
    material.ambientOcclusion = surface.ao;
    // No semantic material tags (foliage/water/portal/...) are compiled into MaterialHeader yet.
    material.materialTags = 0u;
    return material;
}

// No biome sampling reaches closest-hit. A slot sees a neutral 0.5/0.5 placeholder rather than
// fabricated real values.
static const float PLACEHOLDER_BIOME_TEMPERATURE = 0.5;
static const float PLACEHOLDER_BIOME_HUMIDITY = 0.5;

SurfaceInput buildSurfaceInput(FrameContext frame, MaterialInput material, float3 position,
        float3 geometricNormal, float3 shadingNormal, float3 outgoingDirection, float2 uv, float lod) {
    SurfaceInput input;
    input.frame = frame;
    input.material = material;
    input.position = position;
    input.geometricNormal = geometricNormal;
    input.shadingNormal = shadingNormal;
    input.outgoingDirection = outgoingDirection;
    input.textureCoordinate = uv;
    // Approximated from the ray-cone LOD already computed for texture filtering, not a true dUV
    // footprint; the built-in surface does not read this field.
    input.textureFootprint = exp2(lod);
    input.biomeTemperature = PLACEHOLDER_BIOME_TEMPERATURE;
    input.biomeHumidity = PLACEHOLDER_BIOME_HUMIDITY;
    input.surfaceFlags = 0u;
    return input;
}

// Loaded lazily, only by the two surface-slot branches below — particles, water, and glass never
// need it, and this is the hottest chain in the frame (see file doc), so it stays off their path.
FrameContext currentFrameContext() {
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    return frameContext(worldPush, skyState(worldPush));
}

[shader("closesthit")]
void main<TC : IComposition>(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    float2 attribs = attr.barycentrics;
    uint rayCone = payload.rayCone;
    TC.Surface surfaceModel;

    // Particle billboards remain engine-owned and do not route through the surface slot.
    if ((InstanceID() & PARTICLE_BIT) != 0u) {
        uint idx = InstanceID() & IDX_MASK;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[idx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        ConstPtr<uint> ib = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvb = ConstPtr<float2>(g.uvAddr);
        uint p0 = ib[3u * pid + 0u];
        uint p1 = ib[3u * pid + 1u];
        uint p2 = ib[3u * pid + 2u];
        float3 pbary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 puv = pbary.x * uvb[p0] + pbary.y * uvb[p1] + pbary.z * uvb[p2];
        int pslot = int(pr.tint.w + 0.5);
        float3x3 o2w = (float3x3)ObjectToWorld3x4();
        float3 pp0 = mul(o2w, HitTriangleVertexPosition(0));
        float3 pp1 = mul(o2w, HitTriangleVertexPosition(1));
        float3 pp2 = mul(o2w, HitTriangleVertexPosition(2));
        float particleLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(pslot)]),
                pp0, pp1, pp2, uvb[p0], uvb[p1], uvb[p2]);
        float3 pn = normalize(pr.normal.xyz);
        if (dot(pn, WorldRayDirection()) > 0.0) {
            pn = -pn;
        }
        float3 particleAlbedo709 = srgbToLinear(
                entityAlbedoTex[NonUniformResourceIndex(pslot)].SampleLevel(puv, particleLod).rgb)
                * srgbToLinear(pr.tint.rgb);
        packAlbedo(payload, bt709ToAcesCg(particleAlbedo709));
        packNormal(payload, pn);
        payload.hitT = RayTCurrent();
        if (g.dispAddr != 0) {
            ConstPtr<float4> pd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = half3(pbary.x * pd[p0].xyz + pbary.y * pd[p1].xyz + pbary.z * pd[p2].xyz);
        } else {
            payload.motionPrev = half3(g.rigidDisp.xyz);
        }
        payload.f0 = half3(0.0h, 0.0h, 0.0h);
        payloadSetPacked(payload, MATERIAL_PARTICLE, 1.0, 0.0, 0.0, 0.0,
                1.0, 0.0, EMISSION_SOURCE_NONE);
        return;
    }
    // Dynamic entities: captured ModelPart geometry routed through the surface slot.
    if ((InstanceID() & ENTITY_BIT) != 0u) {
        uint eidx = InstanceID() & ~ENTITY_BIT;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[eidx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        float3x3 entityO2w = (float3x3)ObjectToWorld3x4();
        float3 vdir = -WorldRayDirection();
        float3 geometricNormal = normalize(mul(entityO2w, pr.normal.xyz));
        bool entering = dot(WorldRayDirection(), geometricNormal) < 0.0;
        if (dot(geometricNormal, vdir) < 0.0) {
            geometricNormal = -geometricNormal;
        }
        ConstPtr<uint> eidxBuf = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> euv = ConstPtr<float2>(g.uvAddr);
        uint e0 = eidxBuf[3u * pid + 0u];
        uint e1 = eidxBuf[3u * pid + 1u];
        uint e2 = eidxBuf[3u * pid + 2u];
        float3 ebary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 euvCoord = ebary.x * euv[e0] + ebary.y * euv[e1] + ebary.z * euv[e2];
        int texSlot = int(pr.tint.w + 0.5);
        float3 ep0 = mul(entityO2w, HitTriangleVertexPosition(0));
        float3 ep1 = mul(entityO2w, HitTriangleVertexPosition(1));
        float3 ep2 = mul(entityO2w, HitTriangleVertexPosition(2));
        float entityLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(texSlot)]),
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2]);
        float4 entityTexel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(euvCoord, entityLod);
        MaterialHeader header = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
        uint material = header.model;
        float3 baseAlbedo709 = srgbToLinear(entityTexel.rgb) * srgbToLinear(pr.tint.rgb);
        float3 opticalColor709 = material == MATERIAL_DIELECTRIC
                ? lerp(float3(1.0), baseAlbedo709, entityTexel.a) : baseAlbedo709;
        float3 albedo = bt709ToAcesCg(opticalColor709);

        if (material != MATERIAL_OPAQUE) {
            // Dielectric/transmissive entities stay on the engine material path.
            Surface surface = evaluateMaterial(header, euvCoord, entityLod, albedo, geometricNormal,
                    ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir,
                    header.params.x, header.params.y, pr.normal.w, false);
            packAlbedo(payload, albedo * surface.ao);
            packNormal(payload, surface.normal);
            payload.hitT = RayTCurrent();
            if (g.dispAddr != 0) {
                ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
                payload.motionPrev = half3(ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz);
            } else {
                payload.motionPrev = half3(g.rigidDisp.xyz);
            }
            payload.f0 = half3(surface.f0);
            payloadSetPacked(payload, material, surface.roughness, surface.metalness,
                    0.0, 0.0, header.params.z, header.params.w, EMISSION_SOURCE_NONE);
            payloadSetDielectric(payload, material, entering);
            return;
        }

        Surface surface = evaluateMaterial(header, euvCoord, entityLod, albedo, geometricNormal,
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir,
                header.params.x, header.params.y, pr.normal.w, true);

        MaterialInput material0 = buildMaterialInput(surface, albedo, header.params.z);
        float3 hitPos = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
        SurfaceInput input = buildSurfaceInput(currentFrameContext(), material0, hitPos,
                geometricNormal, surface.normal, vdir, euvCoord, entityLod);
        SurfaceClosure closure = surfaceModel.createSurface(input);

        packAlbedo(payload, closure.diffuseAlbedo);
        packNormal(payload, closure.shadingNormal);
        payload.hitT = RayTCurrent();
        if (g.dispAddr != 0) {
            ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = half3(ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz);
        } else {
            payload.motionPrev = half3(g.rigidDisp.xyz);
        }
        payload.f0 = half3(closure.specularF0);
        float emission = surface.emission;
        payloadSetPacked(payload, material, closure.alphaRoughness, 0.0,
                emission, closure.subsurface, header.params.z, closure.transmission,
                materialEmissionSource(header, emission));
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    uint pid = PrimitiveIndex() + sec.triBase[GeometryIndex()];
    TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[pid];
    MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
    float3 geometricNormal = normalize(pr.normal.xyz);
    float3 tint = pr.tint.rgb;

    bool entering = dot(WorldRayDirection(), geometricNormal) < 0.0;

    ConstPtr<float2> uvs = ConstPtr<float2>(sec.uvAddr);
    float2 uv0 = uvs[3u * pid + 0u];
    float2 uv1 = uvs[3u * pid + 1u];
    float2 uv2 = uvs[3u * pid + 2u];
    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    float2 uv = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    float3 tp0 = HitTriangleVertexPosition(0);
    float3 tp1 = HitTriangleVertexPosition(1);
    float3 tp2 = HitTriangleVertexPosition(2);
    float blockLod = rayConeTextureLod(rayCone, texSizePx(blockAlbedoAtlas), tp0, tp1, tp2, uv0, uv1, uv2);

    float3 vdir = -WorldRayDirection();
    if (dot(geometricNormal, vdir) < 0.0) {
        geometricNormal = -geometricNormal;
    }

    // Stained glass and ice stay on the engine dielectric path.
    if (materialHeader.model == MATERIAL_DIELECTRIC) {
        float4 gtex = blockAlbedoAtlas.SampleLevel(uv, blockLod);
        float3 gtex709 = srgbToLinear(gtex.rgb);
        float3 tint709 = srgbToLinear(tint);
        float3 opticalColor709 = lerp(float3(1.0), gtex709 * tint709, gtex.a);
        float3 glassAlbedo = bt709ToAcesCg(opticalColor709);
        Surface glassSurface = evaluateMaterial(materialHeader, uv, blockLod, glassAlbedo, geometricNormal,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                materialHeader.params.x, materialHeader.params.y, 0.0, false);
        float3 n = glassSurface.normal;
        packAlbedo(payload, bt709ToAcesCg(applyBreaking(
                opticalColor709 * glassSurface.ao, rayCone, WorldRayOrigin(),
                WorldRayDirection(), RayTCurrent(), n)));
        packNormal(payload, n);
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        payload.f0 = half3(glassSurface.f0);
        payloadSetPacked(payload, MATERIAL_DIELECTRIC, glassSurface.roughness,
                glassSurface.metalness, 0.0, 0.0, materialHeader.params.z,
                materialHeader.params.w, EMISSION_SOURCE_NONE);
        payloadSetDielectric(payload, MATERIAL_DIELECTRIC, entering);
        return;
    }

    float3 tint709 = srgbToLinear(tint);
    float3 albedo709 = srgbToLinear(blockAlbedoAtlas.SampleLevel(uv, blockLod).rgb) * tint709;

    if (materialHeader.model == MATERIAL_WATER) {
        // Water stays on the engine dielectric path.
        packAlbedo(payload, bt709ToAcesCg(tint709));
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        float ew = pr.normal.w;
        bool nonSolid = ew >= 1.5;
        float emission = nonSolid ? ew - 2.0 : ew;
        Surface surface = evaluateMaterial(materialHeader, uv, blockLod, unpackAlbedo(payload), geometricNormal,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                materialHeader.params.x, materialHeader.params.y, emission, false);
        packAlbedo(payload, unpackAlbedo(payload) * surface.ao);
        packNormal(payload, surface.normal);
        payload.f0 = half3(surface.f0);
        payloadSetPacked(payload, MATERIAL_WATER, surface.roughness, surface.metalness,
                surface.emission, surface.sss, materialHeader.params.z, materialHeader.params.w,
                materialEmissionSource(materialHeader, surface.emission));
        payloadSetDielectric(payload, MATERIAL_WATER, entering);
        return;
    }

    // Opaque terrain: the reflective path routed through the surface slot.
    packAlbedo(payload, bt709ToAcesCg(albedo709));
    payload.hitT = RayTCurrent();
    payload.motionPrev = half3(0.0h, 0.0h, 0.0h);

    float ew = pr.normal.w;
    bool nonSolid = ew >= 1.5;
    float emission = nonSolid ? ew - 2.0 : ew;
    Surface surface = evaluateMaterial(materialHeader, uv, blockLod, unpackAlbedo(payload), geometricNormal,
            tp0, tp1, tp2, uv0, uv1, uv2, vdir,
            materialHeader.params.x, materialHeader.params.y, emission, nonSolid);

    // Block-breaking overlay is a rendering trick, not a material fact,
    // so it stays engine-side and composites into baseColor before the slot sees it.
    float3 brokenAlbedo709 = applyBreaking(albedo709, rayCone, WorldRayOrigin(),
            WorldRayDirection(), RayTCurrent(), surface.normal);
    float3 finalAlbedo = bt709ToAcesCg(brokenAlbedo709);

    MaterialInput material0 = buildMaterialInput(surface, finalAlbedo, materialHeader.params.z);
    float3 hitPos = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
    SurfaceInput input = buildSurfaceInput(currentFrameContext(), material0, hitPos,
            geometricNormal, surface.normal, vdir, uv, blockLod);
    SurfaceClosure closure = surfaceModel.createSurface(input);

    packAlbedo(payload, closure.diffuseAlbedo);
    packNormal(payload, closure.shadingNormal);
    payload.f0 = half3(closure.specularF0);
    payloadSetPacked(payload, MATERIAL_OPAQUE, closure.alphaRoughness, 0.0,
            surface.emission, closure.subsurface, materialHeader.params.z, closure.transmission,
            materialEmissionSource(materialHeader, surface.emission));
    // RIS emitter-NEE membership: raygen gates this emitter's direct-hit emission term (RIS covers it).
    if ((pr.flags & TERRAIN_PRIM_IN_LIGHT_BUFFER) != 0u) {
        payload.flags |= PAYLOAD_EMITTER_IN_LIST;
    }
}
