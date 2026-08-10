// Composition-generic closest-hit shader. Geometry decode, texture sampling, source-format decode,
// ray-cone LOD, and the block-breaking overlay stay engine-owned; every surface that does not bound a
// medium — terrain, entities, block entities, particle billboards alike — is routed through the selected
// surface slot's createSurface implementation.
//
// WorldShaderCompiler specializes this generic entry point with the active composition.
// Water, stained glass and ice stay on an engine-owned path because a dielectric transition refracts and
// pushes a medium, which is transport rather than surface-slot policy.
import world_common;
import math;
import bindings;
import caustica_types;
import caustica_surface;
import caustica_api;
// Required in EVERY composition-specialized entry point, not just the ones that read a pass resource:
// Slang rejects a module that declares global shader parameters if it is reachable only through the
// generic type parameter, and TC exposes every slot the composition names, whether this stage calls it or
// not. See sky_miss.slang for the full rule and WorldShaderCompiler.passResourceAnchorModule for how this
// fixed name is generated.
import caustica_pass_resources;

// `roughness` is OpenPBR's perceptual specular_roughness, matching what the payload carries end to end.
// `materialFlags` is PAYLOAD_TRANSMISSIVE | PAYLOAD_WATER as they apply to this hit (0 for opaque).
void payloadSetPacked(inout Payload payload, uint materialFlags, float roughness, float metalness,
        float emission, float ior, float transmission) {
    // PAYLOAD_GUIDE_RAY describes the ray, not the hit, so it is carried across rather than overwritten
    // with the rest of the word. Everything else in flags is this hit's own output.
    payload.flags = materialFlags | (payload.flags & PAYLOAD_GUIDE_RAY);
    payload.roughMetal = packHalf2(float2(roughness, metalness));
    payload.emission = packHalf2(float2(emission, 0.0));
    payload.iorTransmission = packHalf2(float2(ior, transmission));
}

void payloadSetDielectric(inout Payload payload, bool entering) {
    if (entering) payload.flags |= PAYLOAD_DIELECTRIC_ENTERING;
}

// A guide is a control signal, not a sample that averages out, so it needs one answer about which surface
// it is looking at. A surface covering too little of its footprint is not that answer: report it as a
// crossing and let the walk take the content behind it instead.
//
// Only coverage decides this. A thin wall's diffuse transmission does not make it see-through — light
// passes through a leaf, but what the guide is looking at is still the leaf. Seeing past a surface is a
// refractive crossing, and those never reach here. One global threshold; per-material tuning would need a
// consumer first.
static const float GUIDE_COVERAGE_THRESHOLD = 0.5;

bool guideCoverageCrossing(Payload payload, float coverage) {
    return (payload.flags & PAYLOAD_GUIDE_RAY) != 0u
            && clamp(coverage, 0.0, 1.0) <= GUIDE_COVERAGE_THRESHOLD;
}

/**
 * Report this hit as a crossing rather than an endpoint: a dielectric with IOR 1. With no index contrast
 * it neither bends the ray nor bounds a medium, which is what a hole in a surface is — so no
 * entering/exiting bit is written, because there is no interior for it to be about. `opticalColor`
 * filters what the walk finds behind it: neutral where the surface is absent, its own colour where it
 * covers.
 */
void payloadSetCoverageCrossing(inout Payload payload, float3 opticalColor, float3 normal) {
    packAlbedo(payload, opticalColor);
    packNormal(payload, normal);
    payload.f0 = half3(0.0h, 0.0h, 0.0h);
    payloadSetPacked(payload, PAYLOAD_TRANSMISSIVE, 0.0, 0.0, 0.0, 1.0, 1.0);
}

/** The surface parameters a binding names. Loaded only here: no any-hit reads them. */
SurfaceMaterial loadSurfaceMaterial(MaterialBinding binding) {
    return ConstPtr<SurfaceMaterial>(pc.materialSurfaceAddr)[binding.surface];
}

MaterialBinding loadBinding(uint materialId) {
    return ConstPtr<MaterialBinding>(pc.materialTableAddr)[materialId];
}

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

float3 perturbNormal(float3 n, float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2, float3 vdir, float2 ntex) {
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
    float2 nxy = ntex * 2.0 - 1.0;
    float nz = sqrt(max(0.0, 1.0 - dot(nxy, nxy)));
    float3 nm = normalize(nxy.x * T + nxy.y * B + nz * n);
    float NoV = dot(nm, vdir);
    if (NoV < 0.02) {
        nm = normalize(nm + vdir * (0.02 - NoV));
    }
    return nm;
}

/**
 * The OpenPBR parameters one hit resolves: the material's uniform values, overridden per texel wherever
 * the canonical pages carry authored data. Names and meanings are the spec's, so `specularRoughness` is
 * perceptual r and reflectance is absent — derive it with `openPbrSpecularF0`.
 */
struct DecodedSurface {
    float3 normal;            // OpenPBR geometry_normal: the shading normal after the normal map
    float3 baseColor;
    float3 specularColor;
    float specularRoughness;
    float baseMetalness;
    float specularIor;
    float emissionLuminance;  // cd/m²
    float transmissionWeight;
};

float3 decodedSpecularF0(DecodedSurface surface) {
    return openPbrSpecularF0(surface.baseColor, surface.baseMetalness, surface.specularIor,
            surface.specularColor);
}

float4 samplePageSurface0(uint page, float2 uv, float lod) {
    return materialSurface0Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}
float4 samplePageSurface1(uint page, float2 uv, float lod) {
    return materialSurface1Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}
float4 samplePageNormal(uint page, float2 uv, float lod) {
    return materialNormalTex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}

// `stateEmission` is the per-primitive block-light level: what an emitter with no authored mask emits,
// and what a heuristic mask modulates.
DecodedSurface evaluateMaterial(SurfaceMaterial mat, float2 atlasUv, float lod, float3 baseColor,
        float3 geometricNormal, float3 p0, float3 p1, float3 p2,
        float2 uv0, float2 uv1, float2 uv2, float3 vdir, float stateEmission) {
    DecodedSurface surface;
    surface.normal = geometricNormal;
    surface.baseColor = baseColor;
    surface.specularColor = float3(1.0, 1.0, 1.0);
    surface.specularRoughness = mat.specularRoughness;
    surface.baseMetalness = mat.baseMetalness;
    surface.specularIor = mat.specularIor;
    surface.emissionLuminance = stateEmission;
    surface.transmissionWeight = mat.transmissionWeight;

    float2 localUv = clamp((atlasUv - mat.albedoUv.xy) * mat.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    float2 pageUv = mat.materialUv.xy + localUv * mat.materialUv.zw;
    float materialLod = min(lod, surfacePageMaxLod(mat));
    uint page = surfacePage(mat);
    if ((mat.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_EMISSION_MASK)) != 0u) {
        float4 surface0 = samplePageSurface0(page, pageUv, materialLod);
        if ((mat.features & MATERIAL_FEATURE_SPEC) != 0u) {
            float4 surface1 = samplePageSurface1(page, pageUv, materialLod);
            surface.specularRoughness = surface0.r;
            surface.baseMetalness = surface0.g;
            surface.emissionLuminance = surface0.b;
            // The source format's subsurface channel: on a surface with no interior that IS diffuse
            // transmission, so it lands directly on the transmission weight. A dielectric's own
            // transmission is refractive and uniform, so those paths take the material's value instead
            // of this one.
            surface.transmissionWeight = surface0.a;
            surface.specularIor = surfaceIorFromPage(surface1.a);
            // One page channel, two OpenPBR parameters, because the source format allows only one: a
            // LabPBR texel authors either a conductor's reflectance — which IS base_color for a metal —
            // or a dielectric's specular tint, and its own metalness says which. Both reduce to the same
            // reflectance through openPbrSpecularF0.
            float3 authored = clamp(bt709ToAcesCg(surface1.rgb), float3(0.0), float3(1.0));
            surface.baseColor = lerp(baseColor, authored, surface.baseMetalness);
            surface.specularColor = lerp(authored, float3(1.0, 1.0, 1.0), surface.baseMetalness);
        } else {
            surface.emissionLuminance = surface0.b * stateEmission;
        }
    }
    surface.emissionLuminance *= surfaceEmissionLuminance(mat);
    if ((mat.features & MATERIAL_FEATURE_NORMAL) != 0u) {
        float4 normalTexel = samplePageNormal(page, pageUv, materialLod);
        surface.normal = perturbNormal(geometricNormal, p0, p1, p2, uv0, uv1, uv2,
                vdir, normalTexel.xy);
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

// Builds the OpenPBR description handed to createSurface. The base colour is ACEScg and already carries
// the block-breaking overlay; the metalness split is the surface slot's job, so nothing is pre-multiplied
// here.
//
// Everything reaching this point is thin-walled by construction: a surface that refracts and bounds a
// medium was routed to the engine's dielectric transport instead, so nothing left here has an interior
// for light to travel through. That is what makes its transmission weight the diffuse kind.
MaterialInput buildMaterialInput(DecodedSurface surface) {
    MaterialInput material;
    material.baseColor = surface.baseColor;
    material.baseMetalness = surface.baseMetalness;
    material.specularRoughness = surface.specularRoughness;
    material.specularIor = surface.specularIor;
    material.specularColor = surface.specularColor;
    material.transmissionWeight = surface.transmissionWeight;
    // Minecraft's convention is that an emitter glows in its own texture colour, so this adapter — not
    // the model — ties emission_color to base_color.
    //
    // The payload's emission channel is still a scalar multiplied against the final albedo at the read
    // site (indirect_core), not an independent colour, so a slot that computes its own emissionRadiance
    // is not yet threaded back out; the engine's own emission stays authoritative until the payload ABI
    // carries a real emission colour. This is the same representational gap as demodulationAlbedo.
    material.emissionColor = surface.baseColor;
    material.emissionLuminance = max(surface.emissionLuminance, 0.0);
    material.geometryOpacity = 1.0; // opaque bucket only; any-hit already resolved cutout coverage
    material.geometryThinWalled = true;
    // No semantic material tags (foliage/water/portal/...) are compiled into SurfaceMaterial yet.
    material.materialTags = 0u;
    return material;
}

// No biome sampling reaches closest-hit. A slot sees a neutral 0.5/0.5 placeholder rather than
// fabricated real values.
static const float PLACEHOLDER_BIOME_TEMPERATURE = 0.5;
static const float PLACEHOLDER_BIOME_HUMIDITY = 0.5;

SurfaceInput buildSurfaceInput(MaterialInput material, float3 position,
        float3 geometricNormal, float3 shadingNormal, float3 outgoingDirection, float2 uv, float lod) {
    SurfaceInput input;
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

[shader("closesthit")]
void main<TC : IComposition>(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    float2 attribs = attr.barycentrics;
    uint rayCone = payload.rayCone;
    TC.Surface surfaceModel;

    // Captured geometry: dynamic entities, block entities, and particle billboards, all indexing the
    // entity table and all routed through the surface slot. A billboard needs no path of its own — it is
    // a thin surface that answers light from either side, which its material says with a transmission
    // weight of one half.
    if ((InstanceID() & (ENTITY_BIT | PARTICLE_BIT)) != 0u) {
        uint eidx = InstanceID() & IDX_MASK;
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
        MaterialBinding binding = loadBinding(pr.materialId);
        SurfaceMaterial mat = loadSurfaceMaterial(binding);
        uint texSlot = bindingAlbedoSlot(binding);
        float3 ep0 = mul(entityO2w, HitTriangleVertexPosition(0));
        float3 ep1 = mul(entityO2w, HitTriangleVertexPosition(1));
        float3 ep2 = mul(entityO2w, HitTriangleVertexPosition(2));
        float entityLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(texSlot)]),
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2]);
        float4 entityTexel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(euvCoord, entityLod);
        bool transmissive = (bindingFlags(binding) & BINDING_TRANSMISSIVE) != 0u;
        float3 baseAlbedo709 = srgbToLinear(entityTexel.rgb) * srgbToLinear(pr.tint.rgb);
        float3 opticalColor709 = transmissive
                ? lerp(float3(1.0), baseAlbedo709, entityTexel.a) : baseAlbedo709;
        float3 albedo = bt709ToAcesCg(opticalColor709);

        if (transmissive) {
            // Dielectric/transmissive entities stay on the engine material path. Water is terrain-only —
            // nothing produces a water-material entity — so this is a single generic dielectric branch.
            DecodedSurface surface = evaluateMaterial(mat, euvCoord, entityLod, albedo, geometricNormal,
                    ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir, 0.0);
            packAlbedo(payload, surface.baseColor);
            packNormal(payload, surface.normal);
            payload.hitT = RayTCurrent();
            if (g.dispAddr != 0) {
                ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
                payload.motionPrev = half3(ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz);
            } else {
                payload.motionPrev = half3(g.rigidDisp.xyz);
            }
            payload.f0 = half3(decodedSpecularF0(surface));
            payloadSetPacked(payload, PAYLOAD_TRANSMISSIVE, surface.specularRoughness, surface.baseMetalness,
                    0.0, mat.specularIor, mat.transmissionWeight);
            payloadSetDielectric(payload, entering);
            return;
        }

        // Checked before the material pages are sampled and the surface slot runs: a guide walking
        // through foliage or a blended entity pays for neither.
        if (guideCoverageCrossing(payload, entityTexel.a)) {
            payload.hitT = RayTCurrent();
            if (g.dispAddr != 0) {
                ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
                payload.motionPrev = half3(ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz);
            } else {
                payload.motionPrev = half3(g.rigidDisp.xyz);
            }
            payloadSetCoverageCrossing(payload,
                    bt709ToAcesCg(lerp(float3(1.0), baseAlbedo709, entityTexel.a)), geometricNormal);
            return;
        }

        DecodedSurface surface = evaluateMaterial(mat, euvCoord, entityLod, albedo, geometricNormal,
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir, pr.normal.w);

        MaterialInput material0 = buildMaterialInput(surface);
        float3 hitPos = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
        SurfaceInput input = buildSurfaceInput(material0, hitPos,
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
        payloadSetPacked(payload, 0u, sqrt(closure.alphaRoughness), 0.0,
                surface.emissionLuminance, closure.interiorIor, closure.transmission);
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    uint pid = PrimitiveIndex() + sec.triBase[GeometryIndex()];
    TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[pid];
    MaterialBinding terrainBinding = loadBinding(pr.materialId);
    SurfaceMaterial terrainMat = loadSurfaceMaterial(terrainBinding);
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
    // Terrain albedo is the block atlas, reached through the shared bindless array like every other
    // producer; the material record names the slot, so no geometry path carries a texture index.
    uint blockSlot = bindingAlbedoSlot(terrainBinding);
    float blockLod = rayConeTextureLod(rayCone,
            texSizePx(entityAlbedoTex[NonUniformResourceIndex(blockSlot)]), tp0, tp1, tp2, uv0, uv1, uv2);

    float3 vdir = -WorldRayDirection();
    if (dot(geometricNormal, vdir) < 0.0) {
        geometricNormal = -geometricNormal;
    }

    bool transmissive = (bindingFlags(terrainBinding) & BINDING_TRANSMISSIVE) != 0u;
    if (transmissive) {
        // Water and glass/ice both stay on the engine dielectric path. BINDING_RECORD_CROSSING (today
        // water-only) tells them apart for the one thing that differs: water's colour is the
        // per-primitive biome tint with no texture fetch; glass samples its own texture and takes
        // block-breaking.
        bool isWaterMaterial = (bindingFlags(terrainBinding) & BINDING_RECORD_CROSSING) != 0u;
        float3 tint709 = srgbToLinear(tint);
        float3 opticalColor709;
        float emissionInput = 0.0;
        if (isWaterMaterial) {
            opticalColor709 = tint709;
            // The mesher folds a +2 flag for non-SOLID block layers into the emission lane; only the
            // block-light level below it is a material input.
            float ew = pr.normal.w;
            emissionInput = ew >= 1.5 ? ew - 2.0 : ew;
        } else {
            float4 gtex = entityAlbedoTex[NonUniformResourceIndex(blockSlot)].SampleLevel(uv, blockLod);
            float3 gtex709 = srgbToLinear(gtex.rgb);
            opticalColor709 = applyBreaking(lerp(float3(1.0), gtex709 * tint709, gtex.a), rayCone,
                    WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), geometricNormal);
        }
        DecodedSurface surface = evaluateMaterial(terrainMat, uv, blockLod,
                bt709ToAcesCg(opticalColor709), geometricNormal,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir, emissionInput);
        packAlbedo(payload, surface.baseColor);
        packNormal(payload, surface.normal);
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        payload.f0 = half3(decodedSpecularF0(surface));
        payloadSetPacked(payload, PAYLOAD_TRANSMISSIVE | (isWaterMaterial ? PAYLOAD_WATER : 0u),
                surface.specularRoughness, surface.baseMetalness,
                isWaterMaterial ? surface.emissionLuminance : 0.0,
                terrainMat.specularIor, terrainMat.transmissionWeight);
        payloadSetDielectric(payload, entering);
        return;
    }

    float3 tint709 = srgbToLinear(tint);
    float4 blockTexel = entityAlbedoTex[NonUniformResourceIndex(blockSlot)].SampleLevel(uv, blockLod);
    float3 albedo709 = srgbToLinear(blockTexel.rgb) * tint709;

    // The mesher folds a +2 flag for non-SOLID block layers into the emission lane; only the block-light
    // level below it is a material input.
    float ew = pr.normal.w;
    float stateEmission = ew >= 1.5 ? ew - 2.0 : ew;

    // Checked before the material pages are sampled and the surface slot runs: a guide walking through
    // foliage pays for neither.
    if (guideCoverageCrossing(payload, blockTexel.a)) {
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        payloadSetCoverageCrossing(payload,
                bt709ToAcesCg(lerp(float3(1.0), albedo709, blockTexel.a)), geometricNormal);
        return;
    }

    // Opaque terrain: the reflective path routed through the surface slot.
    payload.hitT = RayTCurrent();
    payload.motionPrev = half3(0.0h, 0.0h, 0.0h);

    // Block-breaking overlay is a rendering trick, not a material fact, so it stays engine-side and
    // composites into the texture colour before any material decode sees it. Keyed on the geometric
    // normal: the decal is projected onto the block face, not onto the normal map's perturbation of it.
    float3 brokenAlbedo709 = applyBreaking(albedo709, rayCone, WorldRayOrigin(),
            WorldRayDirection(), RayTCurrent(), geometricNormal);
    DecodedSurface surface = evaluateMaterial(terrainMat, uv, blockLod,
            bt709ToAcesCg(brokenAlbedo709), geometricNormal,
            tp0, tp1, tp2, uv0, uv1, uv2, vdir, stateEmission);

    MaterialInput material0 = buildMaterialInput(surface);
    float3 hitPos = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
    SurfaceInput input = buildSurfaceInput(material0, hitPos,
            geometricNormal, surface.normal, vdir, uv, blockLod);
    SurfaceClosure closure = surfaceModel.createSurface(input);

    packAlbedo(payload, closure.diffuseAlbedo);
    packNormal(payload, closure.shadingNormal);
    payload.f0 = half3(closure.specularF0);
    payloadSetPacked(payload, 0u, sqrt(closure.alphaRoughness), 0.0,
            surface.emissionLuminance, closure.interiorIor, closure.transmission);
    // RIS emitter-NEE membership: raygen gates this emitter's direct-hit emission term (RIS covers it).
    if ((pr.flags & TERRAIN_PRIM_IN_LIGHT_BUFFER) != 0u) {
        payload.flags |= PAYLOAD_EMITTER_IN_LIST;
    }
}
