// Composition-generic closest-hit shader. It resolves geometry, texture footprint and uniform OpenPBR
// values, then hands opaque provider data and source-neutral hit inputs to the registered implementation.
//
// WorldShaderCompiler specializes this generic entry point with the active composition.
//
// Every hit goes through the implementation, including the dielectrics. What differs is what happens
// AFTER: a surface that bounds a medium refracts and pushes one, which is transport and stays
// engine-owned, while everything else resolves to a closure the estimator shades with. Both read the
// same description, so a procedural normal reaches refraction and shading alike.
import world_common;
import math;
import surface_bsdf;
import bindings;
import caustica_texture_resources;
import caustica_types;
import caustica_surface;
import caustica_coverage;
import caustica_api;
// Required in EVERY composition-specialized entry point, not just the ones that read a pass resource:
// Slang rejects a module that declares global shader parameters if it is reachable only through the
// generic type parameter, and TC exposes every slot the composition names, whether this stage calls it or
// not. See sky_miss.slang for the full rule and WorldShaderCompiler.passResourceAnchorModule for how this
// fixed name is generated.
import caustica_pass_resources;

// `roughness` is OpenPBR's perceptual specular_roughness, matching what the payload carries end to end.
// `materialFlags` carries the transport state that applies to this hit (0 for opaque).
void payloadSetPacked(inout Payload payload, uint materialFlags, float roughness,
        float3 emissionRadiance, float ior, float transmission) {
    // PAYLOAD_GUIDE_RAY describes the ray, not the hit, so it is carried across rather than overwritten
    // with the rest of the word. Everything else in flags is this hit's own output.
    payload.flags = materialFlags | (payload.flags & PAYLOAD_GUIDE_RAY);
    float3 finiteEmission = clamp(emissionRadiance, float3(0.0), float3(HALF_MAX));
    payload.roughEmissionB = packHalf2(float2(roughness, finiteEmission.b));
    payload.emissionRG = packHalf2(finiteEmission.rg);
    payload.iorTransmission = packHalf2(float2(ior, transmission));
}

/** Per-frame constants a procedural surface evaluates against; closest-hit reads them one field at a
 * time rather than loading the whole push record for a handful of scalars. */
float frameTime() { return ConstPtr<WorldPush>(pc.worldPushAddr)[0].time; }
float framePreviousTime() { return ConstPtr<WorldPush>(pc.worldPushAddr)[0].previousTime; }
float3 frameProceduralDomainOffset() {
    return ConstPtr<WorldPush>(pc.worldPushAddr)[0].proceduralDomainOffset;
}
bool frameProceduralAnimation() { return (ConstPtr<WorldPush>(pc.worldPushAddr)[0].flags & 16u) != 0u; }

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
    payload.previousNormal = packNormalOct(normal);
    payload.f0 = half3(0.0h, 0.0h, 0.0h);
    payloadSetPacked(payload, PAYLOAD_TRANSMISSIVE, 0.0, float3(0.0), 1.0, 1.0);
}

/** The surface parameters a binding names. Loaded only here: no any-hit reads them. */
SurfaceMaterial loadSurfaceMaterial(MaterialBinding binding) {
    return ConstPtr<SurfaceMaterial>(pc.materialSurfaceAddr)[binding.surface];
}

MaterialBinding loadBinding(uint materialId) {
    return ConstPtr<MaterialBinding>(pc.materialTableAddr)[materialId];
}

float4 interpolateVertexColor(GeometryRecord geometry, uint3 indices, float3 bary) {
    if ((geometry.flags & GEOMETRY_VERTEX_COLORS) == 0u) {
        return float4(1.0);
    }
    ConstPtr<float> colors = ConstPtr<float>(geometry.vertexColorAddress);
    uint c0 = 4u * indices.x;
    uint c1 = 4u * indices.y;
    uint c2 = 4u * indices.z;
    float4 color0 = float4(colors[c0], colors[c0 + 1u], colors[c0 + 2u], colors[c0 + 3u]);
    float4 color1 = float4(colors[c1], colors[c1 + 1u], colors[c1 + 2u], colors[c1 + 3u]);
    float4 color2 = float4(colors[c2], colors[c2 + 1u], colors[c2 + 2u], colors[c2 + 3u]);
    float4 color = bary.x * color0 + bary.y * color1 + bary.z * color2;
    return float4(color.rgb, clamp(color.a, 0.0, 1.0));
}

void loadTriangleTextureCoordinates(GeometryRecord geometry, uint pid, uint3 indices,
        out float2 uv0, out float2 uv1, out float2 uv2) {
    ConstPtr<float2> textureCoordinates = ConstPtr<float2>(geometry.textureCoordinateAddress);
    if ((geometry.flags & GEOMETRY_INDEXED_TEXTURE_COORDINATES) != 0u) {
        uv0 = textureCoordinates[indices.x];
        uv1 = textureCoordinates[indices.y];
        uv2 = textureCoordinates[indices.z];
        return;
    }
    uv0 = textureCoordinates[3u * pid + 0u];
    uv1 = textureCoordinates[3u * pid + 1u];
    uv2 = textureCoordinates[3u * pid + 2u];
}

float3 transformNormalToWorld(float3x3 objectToWorld, float3 objectNormal) {
    float3 basisX = float3(objectToWorld[0][0], objectToWorld[1][0], objectToWorld[2][0]);
    float3 basisY = float3(objectToWorld[0][1], objectToWorld[1][1], objectToWorld[2][1]);
    float3 basisZ = float3(objectToWorld[0][2], objectToWorld[1][2], objectToWorld[2][2]);
    float3 cofactorNormal = objectNormal.x * cross(basisY, basisZ)
            + objectNormal.y * cross(basisZ, basisX)
            + objectNormal.z * cross(basisX, basisY);
    return cofactorNormal / dot(basisX, cross(basisY, basisZ));
}

float3 interpolateShadingNormal(GeometryRecord geometry, uint3 indices, float3 bary,
        float3x3 objectToWorld, float3 orientedFaceNormal) {
    if ((geometry.flags & GEOMETRY_VERTEX_NORMALS) == 0u) {
        return orientedFaceNormal;
    }
    ConstPtr<float> normals = ConstPtr<float>(geometry.vertexNormalAddress);
    uint n0 = 3u * indices.x;
    uint n1 = 3u * indices.y;
    uint n2 = 3u * indices.z;
    float3 normal0 = float3(normals[n0], normals[n0 + 1u], normals[n0 + 2u]);
    float3 normal1 = float3(normals[n1], normals[n1 + 1u], normals[n1 + 2u]);
    float3 normal2 = float3(normals[n2], normals[n2 + 1u], normals[n2 + 2u]);
    float3 objectNormal = bary.x * normal0 + bary.y * normal1 + bary.z * normal2;
    float lengthSquared = dot(objectNormal, objectNormal);
    if (lengthSquared <= 1.0e-12) {
        return orientedFaceNormal;
    }
    float3 shadingNormal = normalize(transformNormalToWorld(objectToWorld, objectNormal));
    // Vertex normals describe the same side as the primitive. Keep an imported winding or normal-sign
    // mismatch from sending the BSDF into the opposite hemisphere after the true face has been oriented.
    if (dot(shadingNormal, orientedFaceNormal) < 0.0) {
        shadingNormal = -shadingNormal;
    }
    float3 viewDirection = -WorldRayDirection();
    float normalToView = dot(shadingNormal, viewDirection);
    if (normalToView < 0.02) {
        shadingNormal = normalize(shadingNormal + viewDirection * (0.02 - normalToView));
    }
    return shadingNormal;
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

void buildTangentFrame(float3 n, float3 p0, float3 p1, float3 p2,
        float2 t0, float2 t1, float2 t2, out float3 tangent, out float3 bitangent) {
    float2 g1 = t1 - t0;
    float2 g2 = t2 - t0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        tangent = float3(0.0);
        bitangent = float3(0.0);
        return;
    }
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    tangent = normalize(traw - n * dot(n, traw));
    bitangent = cross(n, tangent) * (det < 0.0 ? -1.0 : 1.0);
}

// Builds the OpenPBR description handed to the surface implementation. The base colour is ACEScg; the
// metalness split belongs to the closure, so nothing is pre-multiplied here.
//
// `dielectric` is structural input from the binding: a volume has two interfaces and a real interior,
// while every closure path is a thin wall. Surface evaluation cannot rewrite that decision because
// coverage and traversal classification have already happened.
MaterialInput buildMaterialInput(SurfaceMaterial mat, float3 shadingNormal, bool dielectric) {
    MaterialInput material;
    material.geometryNormal = shadingNormal;
    material.previousGeometryNormal = shadingNormal;
    material.baseColor = mat.baseColorFactor.xyz;
    material.baseMetalness = mat.baseMetalness;
    material.specularRoughness = mat.specularRoughness;
    material.specularIor = mat.specularIor;
    material.transmissionWeight = dielectric ? mat.transmissionWeight : 0.0;
    material.transmissionColor = mat.transmissionColorSubsurfaceWeight.xyz;
    material.subsurfaceWeight = dielectric ? 0.0 : mat.transmissionColorSubsurfaceWeight.w;
    material.subsurfaceColor = mat.subsurfaceColorAnisotropy.xyz;
    material.subsurfaceScatterAnisotropy = mat.subsurfaceColorAnisotropy.w;
    material.emissionColor = mat.emissionColor.xyz;
    material.emissionLuminance = max(mat.emissionColor.w, 0.0);
    return material;
}

SurfaceInput buildSurfaceInput(Payload payload, SurfaceMaterial material, uint primitiveAux0,
        float3 position, float3 geometricNormal, float3 tangent, float3 bitangent,
        float3 outgoingDirection, float2 uv, float lod, float rayFootprint,
        float3 tint, float4 vertexColor, float primitiveEmission) {
    SurfaceInput input;
    input.providerData = material.providerData;
    input.position = position;
    input.geometricNormal = geometricNormal;
    input.tangent = tangent;
    input.bitangent = bitangent;
    input.outgoingDirection = outgoingDirection;
    input.textureCoordinate = uv;
    input.textureLod = lod;
    input.baseTextureIndex = primitiveBaseColorTextureIndex(primitiveAux0);
    input.baseTextureFlags = (primitiveBaseTexturePresent(primitiveAux0)
                    ? SURFACE_BASE_TEXTURE_PRESENT : 0u)
            | (primitiveBaseTextureLinear(primitiveAux0)
                    ? SURFACE_BASE_TEXTURE_LINEAR : 0u);
    input.tint = tint;
    input.vertexColor = vertexColor;
    input.primitiveEmission = primitiveEmission;
    input.textureFootprint = rayFootprint
            / max(abs(dot(outgoingDirection, geometricNormal)), 0.2);
    input.proceduralPosition = position + frameProceduralDomainOffset();
    input.time = frameTime();
    input.previousTime = framePreviousTime();
    input.surfaceFlags = ((payload.flags & PAYLOAD_PRIMARY_RAY) != 0u
            ? SURFACE_RECONSTRUCTION_ANCHOR : 0u)
            | (frameProceduralAnimation() ? SURFACE_ANIMATION_ENABLED : 0u);
    return input;
}

SurfaceModifierInput buildSurfaceModifierInput(float3 position, float3 geometricNormal,
        float textureFootprint) {
    SurfaceModifierInput input;
    input.position = position;
    input.geometricNormal = geometricNormal;
    input.textureFootprint = textureFootprint;
    return input;
}

/**
 * The one payload write both routes share. A dielectric publishes what its transport needs — the medium
 * tint, the interface IOR, which side the ray is on — and everything else publishes the closure the
 * estimator shades with. The normal is the same field in both, so a perturbation reaches either.
 */
void writeHitPayload(inout Payload payload, MaterialInput material, uint surfaceImpl,
        uint materialFlags, bool entering, float3 motionPrev, float emissionLuminance) {
    materialFlags |= surfaceImpl << PAYLOAD_SURFACE_IMPL_SHIFT;
    payload.hitT = RayTCurrent();
    payload.motionPrev = half3(motionPrev);
    packNormal(payload, normalize(material.geometryNormal));
    payload.previousNormal = packNormalOct(normalize(material.previousGeometryNormal));
    if ((materialFlags & PAYLOAD_TRANSMISSIVE) != 0u) {
        payload.diffuseTransmissionAlbedo = half3(0.0h, 0.0h, 0.0h);
        payload.thinTransmissionColor = half3(0.0h, 0.0h, 0.0h);
        packAlbedo(payload, material.baseColor);
        payload.f0 = half3(openPbrSpecularF0(material));
        float3 emissionRadiance = max(material.emissionColor, float3(0.0))
                * max(emissionLuminance, 0.0);
        payloadSetPacked(payload, materialFlags, material.specularRoughness, emissionRadiance,
                material.specularIor, material.transmissionWeight);
        if (entering) payload.flags |= PAYLOAD_DIELECTRIC_ENTERING;
        return;
    }
    SurfaceClosure closure = surfaceClosure(material);
    packAlbedo(payload, closure.diffuseAlbedo);
    payload.diffuseTransmissionAlbedo = half3(closure.diffuseTransmissionAlbedo);
    payload.thinTransmissionColor = half3(closure.thinTransmissionColor);
    payload.f0 = half3(closure.specularF0);
    closure.emissionRadiance = max(material.emissionColor, float3(0.0))
            * max(emissionLuminance, 0.0);
    payloadSetPacked(payload, materialFlags, sqrt(closure.alphaRoughness), closure.emissionRadiance,
            closure.thinTransmissionWeight, 0.0);
}

[shader("closesthit")]
void main<TC : IComposition>(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    float2 attribs = attr.barycentrics;
    uint rayCone = payload.rayCone;
    TC.Surfaces surfaces;
    TC.Coverages coverages;
    TC.SurfaceModifiers surfaceModifiers;

    GeometryRecord g = ConstPtr<GeometryRecord>(pc.geometryTableAddr)[InstanceID()];
    uint pid = PrimitiveIndex() + g.triangleBase[GeometryIndex()];
    Prim pr = ConstPtr<Prim>(g.primitiveAddress)[pid];
    MaterialBinding binding = loadBinding(pr.materialId);
    SurfaceMaterial mat = loadSurfaceMaterial(binding);
    float3x3 objectToWorld = (float3x3)ObjectToWorld3x4();
    float3 faceNormal = normalize(transformNormalToWorld(objectToWorld, pr.normal.xyz));

    bool entering = dot(WorldRayDirection(), faceNormal) < 0.0;

    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    float3 tp0 = HitTriangleVertexPosition(0);
    float3 tp1 = HitTriangleVertexPosition(1);
    float3 tp2 = HitTriangleVertexPosition(2);
    float3 currentLocal = bary.x * tp0 + bary.y * tp1 + bary.z * tp2;
    ConstPtr<uint> indices = ConstPtr<uint>(g.indexAddress);
    uint i0 = indices[3u * pid + 0u];
    uint i1 = indices[3u * pid + 1u];
    uint i2 = indices[3u * pid + 2u];
    uint3 vertexIndices = uint3(i0, i1, i2);
    float2 uv0;
    float2 uv1;
    float2 uv2;
    loadTriangleTextureCoordinates(g, pid, vertexIndices, uv0, uv1, uv2);
    float2 uv = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    float3 previousLocal = currentLocal;
    if (g.previousPositionAddress != 0) {
        ConstPtr<float> previousPositions = ConstPtr<float>(g.previousPositionAddress);
        float3 previous0 = float3(previousPositions[3u * i0], previousPositions[3u * i0 + 1u], previousPositions[3u * i0 + 2u]);
        float3 previous1 = float3(previousPositions[3u * i1], previousPositions[3u * i1 + 1u], previousPositions[3u * i1 + 2u]);
        float3 previous2 = float3(previousPositions[3u * i2], previousPositions[3u * i2 + 1u], previousPositions[3u * i2 + 2u]);
        previousLocal = bary.x * previous0 + bary.y * previous1 + bary.z * previous2;
    }
    InstanceHistory history = ConstPtr<InstanceHistory>(pc.instanceHistoryAddr)[InstanceID()];
    float3 previousWorld = float3(dot(history.row0, float4(previousLocal, 1.0)),
            dot(history.row1, float4(previousLocal, 1.0)),
            dot(history.row2, float4(previousLocal, 1.0)));
    float3 motionPrev = mul(ObjectToWorld3x4(), float4(currentLocal, 1.0)) - previousWorld;
    bool texturePresent = primitiveBaseTexturePresent(pr.aux0);
    uint textureIndex = primitiveBaseColorTextureIndex(pr.aux0);
    float textureLod = 0.0;
    if (texturePresent) {
        textureLod = rayConeTextureLod(rayCone,
                texSizePx(providerTextures[NonUniformResourceIndex(textureIndex)]),
                mul(objectToWorld, tp0), mul(objectToWorld, tp1), mul(objectToWorld, tp2), uv0, uv1, uv2);
    }

    float3 vdir = -WorldRayDirection();
    if (dot(faceNormal, vdir) < 0.0) {
        faceNormal = -faceNormal;
    }
    float3 shadingNormal = interpolateShadingNormal(g, vertexIndices, bary, objectToWorld, faceNormal);
    float4 vertexColor = interpolateVertexColor(g, vertexIndices, bary);

    bool transmissive = (bindingFlags(binding) & BINDING_TRANSMISSIVE) != 0u;
    float stateEmission = pr.normal.w;

    // A guide crossing needs only the narrow coverage result. Resolve it before the shading texture path
    // so a rejected hit does not also pay for closest-hit's base-colour sample and material decode.
    if (!transmissive && (payload.flags & PAYLOAD_GUIDE_RAY) != 0u) {
        CoverageInput coverageInput;
        coverageInput.baseTextureIndex = textureIndex;
        coverageInput.flags = (texturePresent ? COVERAGE_TEXTURE_PRESENT : 0u)
                | (primitiveBaseTextureLinear(pr.aux0)
                        ? COVERAGE_TEXTURE_LINEAR : 0u)
                | (primitiveCoverage(pr.aux0) == COVERAGE_OPAQUE ? COVERAGE_MODE_OPAQUE : 0u);
        coverageInput.baseColorFactor = mat.baseColorFactor.xyz;
        coverageInput.textureCoordinate = uv;
        coverageInput.tint = pr.tint.rgb;
        coverageInput.vertexColor = vertexColor;
        float4 resolvedCoverage = coverages.evaluateCoverage(
                bindingSurfaceImpl(binding), coverageInput);
        if (guideCoverageCrossing(payload, resolvedCoverage.a)) {
            payload.hitT = RayTCurrent();
            payload.motionPrev = half3(motionPrev);
            payloadSetCoverageCrossing(payload, resolvedCoverage.rgb, shadingNormal);
            return;
        }
    }

    float3 tangent;
    float3 bitangent;
    buildTangentFrame(shadingNormal, mul(objectToWorld, tp0), mul(objectToWorld, tp1),
            mul(objectToWorld, tp2), uv0, uv1, uv2, tangent, bitangent);
    MaterialInput material = buildMaterialInput(mat, shadingNormal, transmissive);
    float3 hitPos = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
    SurfaceInput input = buildSurfaceInput(payload, mat, pr.aux0, hitPos, faceNormal, tangent, bitangent,
            vdir, uv, textureLod, rayConeHitWidth(rayCone), pr.tint.rgb, vertexColor,
            transmissive ? 0.0 : stateEmission);
    uint surfaceImpl = bindingSurfaceImpl(binding);
    surfaces.evaluateSurface(surfaceImpl, input, material);
    if ((g.flags & GEOMETRY_RECEIVES_PROJECTED_SURFACE_MODIFIERS) != 0u) {
        surfaceModifiers.applySurfaceModifiers(
                buildSurfaceModifierInput(hitPos, faceNormal, rayConeHitWidth(rayCone)), material);
    }

    writeHitPayload(payload, material, surfaceImpl, transmissive ? PAYLOAD_TRANSMISSIVE : 0u,
            entering, motionPrev, transmissive ? 0.0 : material.emissionLuminance);
}
