module engine_pipeline_probe;

import caustica_ray_pack_api;
import caustica_ray_engine_types;
import caustica_direct_lighting_service;
import caustica_ray_engine_integrator;

float3 exerciseEnginePipeline<TPack : IRayPack>(TPack pack) {
    PackFrameContext frame;
    frame.timeSeconds = 0.0;
    frame.frameIndex = 0u;
    frame.cameraPosition = float3(0.0);
    frame.rain = 0.0;
    frame.thunder = 0.0;
    frame.dayFraction = 0.5;
    frame.nightFraction = 0.0;
    frame.sunDirection = normalize(float3(0.3, 1.0, 0.2));
    frame.moonDirection = -frame.sunDirection;
    frame.dimensionFlags = PACK_DIMENSION_HAS_SKY | PACK_DIMENSION_HAS_WEATHER;

    PackMaterialInput material;
    material.baseColor = float3(0.5);
    material.opacity = 1.0;
    material.alphaRoughness = 0.25;
    material.metalness = 0.0;
    material.specularF0 = float3(0.04);
    material.emissionRadiance = float3(0.0);
    material.transmission = 0.0;
    material.interiorIor = 1.5;
    material.subsurface = 0.0;
    material.ambientOcclusion = 1.0;
    material.materialTags = 0u;

    PackSurfaceInput surfaceInput;
    surfaceInput.frame = frame;
    surfaceInput.material = material;
    surfaceInput.position = float3(0.0);
    surfaceInput.geometricNormal = float3(0.0, 1.0, 0.0);
    surfaceInput.shadingNormal = surfaceInput.geometricNormal;
    surfaceInput.outgoingDirection = surfaceInput.geometricNormal;
    surfaceInput.textureCoordinate = float2(0.0);
    surfaceInput.textureFootprint = 0.0;
    surfaceInput.biomeTemperature = 0.5;
    surfaceInput.biomeHumidity = 0.5;
    surfaceInput.surfaceFlags = 0u;

    float coverage = engineEvaluateCoverage(pack, surfaceInput);
    PackSurfaceClosure surface = engineCreateSurface(pack, surfaceInput);
    EngineDirectLightingReservoir directReservoir = engineBeginDirectLighting();

    uint directionalLightCount = pack.getDirectionalLightCount(frame);
    if (directionalLightCount > 0u) {
        EngineDirectLightCandidate sun = engineSampleDirectionalLight(pack, frame, 0u, 0.5,
                float2(0.25, 0.75));
        engineConsiderDirectCandidate(pack, surface, surfaceInput.outgoingDirection,
                1.0, surface.interiorIor, sun, 0.3, directReservoir);
    }

    EngineDirectLightCandidate emissive;
    emissive.direction = normalize(float3(-0.3, 1.0, 0.4));
    emissive.radianceOverProposalPdf = float3(8.0, 4.0, 1.0);
    emissive.distance = 4.0;
    emissive.sourceId = 17u;
    emissive.flags = ENGINE_LIGHT_FINITE_DISTANCE;
    engineConsiderDirectCandidate(pack, surface, surfaceInput.outgoingDirection,
            1.0, surface.interiorIor, emissive, 0.8, directReservoir);

    EngineSurfaceVertexInput vertex;
    vertex.surfaceInput = surfaceInput;
    vertex.surface = surface;
    vertex.pathThroughput = float3(1.0);
    vertex.incidentIor = 1.0;
    vertex.transmittedIor = surface.interiorIor;
    vertex.bsdfRandom = float3(0.8, 0.2, 0.6);
    vertex.bounce = 0u;
    EngineSurfaceVertexOutput shaded = engineShadeSurfaceVertex(pack, vertex,
            directReservoir, 1.0);

    EngineMissInput miss;
    miss.frame = frame;
    miss.direction = normalize(float3(0.0, 1.0, 0.2));
    miss.pathThroughput = shaded.continuationThroughput;
    miss.showCelestialBodies = true;
    float3 missRadiance = engineShadeMiss(pack, miss);

    PackMediumInput mediumInput;
    mediumInput.frame = frame;
    mediumInput.position = surfaceInput.position;
    mediumInput.tint = float3(0.4, 0.7, 0.9);
    mediumInput.density = 0.1;
    mediumInput.biomeTemperature = surfaceInput.biomeTemperature;
    mediumInput.biomeHumidity = surfaceInput.biomeHumidity;
    mediumInput.mediumTag = PACK_MEDIUM_AIR;
    EngineMediumSegmentResult medium = engineIntegrateMediumSegment(pack, mediumInput, 8.0);
    PackPhaseSample phase = engineSampleMediumPhase(pack, medium.medium,
            miss.direction, float2(0.4, 0.7));

    return shaded.radiance + missRadiance + medium.transmittance + medium.emittedRadiance
            + phase.outgoingDirection * phase.throughputWeight + coverage;
}

[shader("compute")]
[numthreads(1, 1, 1)]
void main<TPack : IRayPack>() {
    TPack pack;
    exerciseEnginePipeline(pack);
}
