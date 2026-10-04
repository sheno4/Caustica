module caustica_cloudly_clouds;
import caustica_cloudly_types;

[[vk::push_constant]] CloudlyCloudPush pc;
static const uint CLOUDLY_MAX_COMPONENTS = 64;
static const float CLOUDLY_PI = 3.14159265359;

float3 cloudlyLocalVector(CloudlyCloudComponent component, float3 hostVector) {
    float c = component.inverseSizeAndCos.w, s = component.uvScale.w;
    float2 sourceXY = float2(hostVector.x, -hostVector.z);
    float2 rotated = float2(c * sourceXY.x + s * sourceXY.y, -s * sourceXY.x + c * sourceXY.y);
    return float3(rotated, hostVector.y) * component.inverseSizeAndCos.xyz;
}

float2 cloudlyBoxInterval(CloudlyCloudComponent component, float3 origin, float3 direction, float limit) {
    float3 point = cloudlyLocalVector(component, origin - component.centerAndDensity.xyz);
    float3 ray = cloudlyLocalVector(component, direction);
    float nearDistance = 0.0, farDistance = limit;
    for (uint axis = 0; axis < 3; axis++) {
        if (abs(ray[axis]) < 1.0e-9) {
            if (abs(point[axis]) > 0.5) return float2(0.0);
        } else {
            float2 bounds = float2(-0.5 - point[axis], 0.5 - point[axis]) / ray[axis];
            nearDistance = max(nearDistance, min(bounds.x, bounds.y));
            farDistance = min(farDistance, max(bounds.x, bounds.y));
        }
    }
    return float2(nearDistance, max(nearDistance, farDistance));
}

uint cloudlyHash(uint value) {
    value ^= value >> 16; value *= 0x7feb352du; value ^= value >> 15;
    value *= 0x846ca68bu; return value ^ (value >> 16);
}
uint cloudlyCellHash(int3 cell) {
    return cloudlyHash(uint(cell.x) * 0x9e3779b9u ^ uint(cell.y) * 0x85ebca6bu ^ uint(cell.z) * 0xc2b2ae35u);
}
float3 cloudlyRandom3(int3 cell) {
    uint hash = cloudlyCellHash(cell);
    return float3(hash & 1023u, (hash >> 10) & 1023u, (hash >> 20) & 1023u) / 1024.0;
}
float cloudlyPerlin(float3 point) {
    int3 cell = int3(floor(point)); float3 local = frac(point);
    float3 fade = local * local * local * (local * (local * 6.0 - 15.0) + 10.0);
    float value = 0.0;
    for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        int3 corner = int3(x, y, z);
        float3 gradient = cloudlyRandom3(cell + corner) * 2.0 - 1.0;
        gradient = normalize(gradient + float3(1.0e-5));
        float3 weight = lerp(1.0 - fade, fade, float3(corner));
        value += dot(gradient, local - float3(corner)) * weight.x * weight.y * weight.z;
    }
    return saturate(0.5 + value);
}
float cloudlyWorley(float3 point) {
    int3 cell = int3(floor(point)); float distanceSquared = 3.0;
    for (int z = -1; z <= 1; z++) for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        int3 neighbor = cell + int3(x, y, z);
        float3 delta = float3(neighbor) + cloudlyRandom3(neighbor) - point;
        distanceSquared = min(distanceSquared, dot(delta, delta));
    }
    return 1.0 - saturate(sqrt(distanceSquared));
}
float cloudlyDetailNoise(CloudlyNoiseProfile profile, float3 point, float footprint) {
    float3 sourcePoint = float3(point.x, -point.z, point.y) / max(profile.cellAndOctaves.xyz, 1.0);
    float frequency = 1.0, weight = 1.0, total = 0.0, weights = 0.0;
    float cell = min(profile.cellAndOctaves.x, min(profile.cellAndOctaves.y, profile.cellAndOctaves.z));
    for (uint octave = 0; octave < uint(profile.cellAndOctaves.w); octave++) {
        float resolvedWeight = weight * saturate(1.0 - footprint * frequency / max(cell, 1.0));
        if (resolvedWeight > 0.0) {
            float perlin = profile.fractal.z > 0.0 ? cloudlyPerlin(sourcePoint * frequency) : 0.0;
            float worley = profile.fractal.w > 0.0 ? cloudlyWorley(sourcePoint * frequency) : 0.0;
            total += (perlin * profile.fractal.z + worley * profile.fractal.w)
                    / max(profile.fractal.z + profile.fractal.w, 1.0e-6) * resolvedWeight;
            weights += resolvedWeight;
        }
        frequency *= profile.fractal.x; weight *= profile.fractal.y;
    }
    if (weights == 0.0) return 0.5;
    float value = saturate((total / weights - profile.remap.x) / (profile.remap.y - profile.remap.x));
    return pow(saturate((value - 0.5) * profile.remap.w + 0.5), profile.remap.z);
}

struct CloudlyDensitySample { float density; float voxelStep; };

/** Coarse source shapes retain their UVs; detail modulation is an explicitly declared compatible approximation. */
CloudlyDensitySample cloudlySample(float3 point, float3 direction, float3 footprintX, float3 footprintY, bool details,
        uint2 activeComponents = uint2(0xffffffffu, 0xffffffffu)) {
    CloudlyCloudComponent* components = (CloudlyCloudComponent*)pc.componentsAddress;
    SamplerState sourceSampler = SamplerDescriptorHeap[pc.samplerIndex];
    CloudlyDensitySample result; result.density = 0.0; result.voxelStep = 1.0e20;
    float detailValues[6] = {};
    uint detailValid = 0;
    for (uint index = 0; index < pc.componentCount; index++) {
        if ((activeComponents[index >> 5] & (1u << (index & 31u))) == 0) continue;
        CloudlyCloudComponent component = components[index];
        float3 local = cloudlyLocalVector(component, point - component.centerAndDensity.xyz);
        if (any(abs(local) > 0.50001)) continue;
        float3 uvw = (local + 0.5) * component.uvScale.xyz + component.uvOffset.xyz;
        Texture3D<float4> sourceTexture = ResourceDescriptorHeap[NonUniformResourceIndex(component.textureIndex)];
        float3 dimensions = component.textureDimensions.xyz;
        float3 worldVoxels = 1.0 / (component.inverseSizeAndCos.xyz * dimensions);
        float3 rayInTexels = abs(cloudlyLocalVector(component, direction) * dimensions);
        result.voxelStep = min(result.voxelStep, 1.0 / max(max(rayInTexels.x, rayInTexels.y), max(rayInTexels.z, 1.0e-9)));
        float footprint = max(length(cloudlyLocalVector(component, footprintX) * dimensions),
                length(cloudlyLocalVector(component, footprintY) * dimensions));
        float lod = clamp(log2(max(footprint, 1.0)), 0.0, min(pc.detailParameters.x, float(component.mipLevels - 1u)));
        float sample = sourceTexture.SampleLevel(sourceSampler, uvw, lod).r;
        if (details && sample > 0.0 && sample < 0.999) {
            CloudlyNoiseProfile* profiles = (CloudlyNoiseProfile*)(pc.componentsAddress
                    + uint64_t(pc.componentCount) * uint64_t(sizeof(CloudlyCloudComponent)));
            float edgeWeight = 4.0 * sample * (1.0 - sample);
            float voxelWidth = max(worldVoxels.x, max(worldVoxels.y, worldVoxels.z));
            float worldFootprint = max(length(footprintX), length(footprintY));
            for (uint layer = 0; layer < 2; layer++) {
                uint profileIndex = component.layerIndex * 2u + layer;
                CloudlyNoiseProfile profile = profiles[profileIndex];
                if (profile.displacement.x <= 0.0) continue;
                // Profiles, world position and footprint are shared by components of the same layer.
                if ((detailValid & (1u << profileIndex)) == 0) {
                    detailValues[profileIndex] = cloudlyDetailNoise(profile, point, worldFootprint);
                    detailValid |= 1u << profileIndex;
                }
                float noise = detailValues[profileIndex];
                float bottomDistance = (local.z + 0.5) / component.inverseSizeAndCos.z;
                float fade = profile.displacement.w > 0.0 ? smoothstep(0.0, profile.displacement.w, bottomDistance) : 1.0;
                float change = (noise - 0.5 - profile.displacement.y) * profile.displacement.x / max(voxelWidth, 1.0);
                sample = saturate(sample + clamp(change, -0.45, 0.45) * edgeWeight * fade);
            }
        }
        result.density = max(result.density, max(0.0, sample) * component.centerAndDensity.w);
    }
    return result;
}

float cloudlyShadow(float3 point, float3 lightDirection) {
    float opticalDepth = 0.0, distance = 0.0;
    float segment = pc.traceParameters.w;
    for (uint sample = 0; sample < 6; sample++) {
        opticalDepth += cloudlySample(point + lightDirection * (distance + segment * 0.5), lightDirection,
                float3(0.0), float3(0.0), false).density
                * pc.opticalParameters.x * segment;
        distance += segment;
        segment *= pc.shadowParameters.x;
    }
    return opticalDepth;
}

float cloudlyHG(float cosine, float g) {
    return (1.0 - g * g) / (4.0 * CLOUDLY_PI * pow(max(1.0 + g * g - 2.0 * g * cosine, 1.0e-5), 1.5));
}

float3 cloudlyUnproject(float2 uv, float depth) {
    float4 position = pc.clipX * (uv.x * 2.0 - 1.0) + pc.clipY * (uv.y * 2.0 - 1.0)
            + pc.clipZ * depth + pc.clipW;
    return position.xyz / position.w;
}

public void runCloudlyClouds(uint3 id) {
    RWTexture2D<float4> output = ResourceDescriptorHeap[pc.destinationIndex];
    uint width, height; output.GetDimensions(width, height);
    if (any(id.xy >= uint2(width, height))) return;
    Texture2D<float4> scene = ResourceDescriptorHeap[pc.sceneIndex];
    float4 background = scene.Load(int3(id.xy, 0));
    float2 uv = (float2(id.xy) + 0.5) / float2(width, height);
    float3 direction = normalize(cloudlyUnproject(uv, 1.0));
    float3 rayDeltaX = normalize(cloudlyUnproject(uv + float2(1.0 / float(width), 0), 1.0)) - direction;
    float3 rayDeltaY = normalize(cloudlyUnproject(uv + float2(0, 1.0 / float(height)), 1.0)) - direction;
    Texture2D<float> depth = ResourceDescriptorHeap[pc.depthIndex];
    uint depthWidth, depthHeight; depth.GetDimensions(depthWidth, depthHeight);
    int2 depthPixel = clamp(int2(floor(uv * float2(depthWidth, depthHeight) - pc.traceParameters.xy)),
            int2(0), int2(depthWidth, depthHeight) - 1);
    float firstHit = depth.Load(int3(depthPixel, 0));
    float2 depthUv = (float2(depthPixel) + 0.5 + pc.traceParameters.xy) / float2(depthWidth, depthHeight);
    float limit = firstHit > 0.0 ? min(length(cloudlyUnproject(depthUv, firstHit)) * pc.cameraAndUnits.w,
            pc.traceParameters.z) : pc.traceParameters.z;

    CloudlyCloudComponent* components = (CloudlyCloudComponent*)pc.componentsAddress;
    float2 intervals[CLOUDLY_MAX_COMPONENTS] = {};
    uint2 activeComponents = uint2(0);
    uint count = 0;
    for (uint component = 0; component < pc.componentCount; component++) {
        float2 interval = cloudlyBoxInterval(components[component], pc.cameraAndUnits.xyz, direction, limit);
        if (interval.y <= interval.x) continue;
        activeComponents[component >> 5] |= 1u << (component & 31u);
        uint at = count;
        while (at > 0 && intervals[at - 1].x > interval.x) {
            intervals[at] = intervals[at - 1];
            at--;
        }
        intervals[at] = interval;
        count++;
    }
    if (count == 0) { output[id.xy] = background; return; }
    uint merged = 0;
    for (uint interval = 0; interval < count; interval++) {
        if (merged > 0 && intervals[interval].x <= intervals[merged - 1].y) {
            intervals[merged - 1].y = max(intervals[merged - 1].y, intervals[interval].y);
        } else {
            intervals[merged++] = intervals[interval];
        }
    }
    float cosine = dot(direction, pc.sunDirectionAndIlluminance.xyz);
    float transmittance = 1.0;
    float3 scattering = 0.0;
    uint sampleIndex = 0;
    // A finite sample budget bounds cost. Empty coarse steps are an approximation, not conservative SDF traversal.
    for (uint interval = 0; interval < merged && transmittance > 0.001 && sampleIndex < pc.steps; interval++) {
        float at = intervals[interval].x;
        while (at < intervals[interval].y && transmittance > 0.001 && sampleIndex < pc.steps) {
            float3 start = pc.cameraAndUnits.xyz + direction * at;
            CloudlyDensitySample coarse = cloudlySample(start, direction, rayDeltaX * at, rayDeltaY * at, false, activeComponents);
            float segment = coarse.voxelStep * pc.shadowParameters.y;
            if (coarse.density <= 1.0e-6) segment *= pc.shadowParameters.w;
            else segment = min(segment, pc.shadowParameters.z / max(coarse.density * pc.opticalParameters.x, 1.0e-7));
            segment = min(max(segment, 0.01), intervals[interval].y - at);
            float midpoint = at + segment * 0.5;
            float3 point = pc.cameraAndUnits.xyz + direction * midpoint;
            float density = cloudlySample(point, direction, rayDeltaX * midpoint, rayDeltaY * midpoint, true, activeComponents).density;
            if (density * pc.opticalParameters.x * segment > pc.shadowParameters.z) {
                segment = min(segment, pc.shadowParameters.z / (density * pc.opticalParameters.x));
                midpoint = at + segment * 0.5;
                point = pc.cameraAndUnits.xyz + direction * midpoint;
                density = cloudlySample(point, direction, rayDeltaX * midpoint, rayDeltaY * midpoint, true, activeComponents).density;
            }
            if (density > 0.0) {
                float segmentT = exp(-min(density * pc.opticalParameters.x * segment, 80.0));
                float opticalDepth = pc.sunDirectionAndIlluminance.w > 0.0
                        ? cloudlyShadow(point, pc.sunDirectionAndIlluminance.xyz) : 0.0;
                float3 illumination = 0.0;
                float attenuation = 1.0, contribution = 1.0, eccentricity = 1.0;
                for (uint order = 0; order < uint(pc.multipleScattering.x); order++) {
                    float phase = lerp(cloudlyHG(cosine, pc.opticalParameters.y * eccentricity),
                            cloudlyHG(cosine, pc.opticalParameters.z * eccentricity), pc.opticalParameters.w)
                            * pc.lightingParameters.y;
                    illumination += contribution * (pc.ambientColor.rgb * pc.lightingParameters.x
                            + pc.sunColor.rgb * pc.sunDirectionAndIlluminance.w * phase * exp(-min(opticalDepth * attenuation, 80.0)));
                    attenuation *= pc.multipleScattering.y; contribution *= pc.multipleScattering.z;
                    eccentricity *= pc.multipleScattering.w;
                }
                scattering += transmittance * (1.0 - segmentT) * pc.lightingParameters.z * illumination;
                transmittance *= segmentT;
            }
            at += segment; sampleIndex++;
        }
    }
    output[id.xy] = float4(background.rgb * transmittance + scattering * pc.lightingParameters.w, background.a);
}
