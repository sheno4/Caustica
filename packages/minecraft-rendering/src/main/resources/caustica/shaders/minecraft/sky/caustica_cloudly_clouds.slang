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

/** Max-union of the preserved red samples; this operation is an explicit Caustica approximation. */
float cloudlyDensity(float3 point) {
    CloudlyCloudComponent* components = (CloudlyCloudComponent*)pc.componentsAddress;
    SamplerState sourceSampler = SamplerDescriptorHeap[pc.samplerIndex];
    float density = 0.0;
    for (uint index = 0; index < pc.componentCount; index++) {
        CloudlyCloudComponent component = components[index];
        float3 local = cloudlyLocalVector(component, point - component.centerAndDensity.xyz);
        if (any(abs(local) > 0.5)) continue;
        float3 uvw = (local + 0.5) * component.uvScale.xyz + component.uvOffset.xyz;
        Texture3D<float4> sourceTexture = ResourceDescriptorHeap[NonUniformResourceIndex(component.textureIndex)];
        float sample = sourceTexture.SampleLevel(sourceSampler, uvw, 0.0).r;
        density = max(density, max(0.0, sample) * component.centerAndDensity.w);
    }
    return density;
}

float cloudlyShadow(float3 point, float3 lightDirection) {
    float opticalDepth = 0.0, distance = 0.0;
    float segment = pc.traceParameters.w;
    for (uint sample = 0; sample < 6; sample++) {
        opticalDepth += cloudlyDensity(point + lightDirection * (distance + segment * 0.5))
                * pc.opticalParameters.x * segment;
        distance += segment;
        segment *= pc.shadowParameters.x;
    }
    return exp(-min(opticalDepth, 80.0));
}

float cloudlyHG(float cosine, float g) {
    return (1.0 - g * g) / (4.0 * CLOUDLY_PI * pow(max(1.0 + g * g - 2.0 * g * cosine, 1.0e-5), 1.5));
}

float3 cloudlyUnproject(float2 uv, float depth) {
    float4 position = pc.clipX * (uv.x * 2.0 - 1.0) + pc.clipY * (uv.y * 2.0 - 1.0)
            + pc.clipZ * depth + pc.clipW;
    return position.xyz / position.w;
}

float cloudlySampleOffset(uint2 pixel, uint sample) {
    uint hash = pixel.x * 0x9e3779b9u ^ pixel.y * 0x85ebca6bu ^ sample * 0xc2b2ae35u
            ^ asuint(pc.shadowParameters.y) * 0x27d4eb2du;
    hash ^= hash >> 16; hash *= 0x7feb352du; hash ^= hash >> 15;
    return 0.2 + 0.6 * float(hash >> 8) / 16777216.0;
}

public void runCloudlyClouds(uint3 id) {
    RWTexture2D<float4> output = ResourceDescriptorHeap[pc.destinationIndex];
    uint width, height; output.GetDimensions(width, height);
    if (any(id.xy >= uint2(width, height))) return;
    Texture2D<float4> scene = ResourceDescriptorHeap[pc.sceneIndex];
    float4 background = scene.Load(int3(id.xy, 0));
    float2 uv = (float2(id.xy) + 0.5 + pc.traceParameters.xy) / float2(width, height);
    float3 direction = normalize(cloudlyUnproject(uv, 1.0));
    Texture2D<float> depth = ResourceDescriptorHeap[pc.depthIndex];
    float firstHit = depth.Load(int3(id.xy, 0));
    float limit = firstHit > 0.0 ? min(length(cloudlyUnproject(uv, firstHit)) * pc.cameraAndUnits.w,
            pc.traceParameters.z) : pc.traceParameters.z;

    CloudlyCloudComponent* components = (CloudlyCloudComponent*)pc.componentsAddress;
    float2 intervals[CLOUDLY_MAX_COMPONENTS] = {};
    uint count = 0;
    for (uint component = 0; component < pc.componentCount; component++) {
        float2 interval = cloudlyBoxInterval(components[component], pc.cameraAndUnits.xyz, direction, limit);
        if (interval.y <= interval.x) continue;
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
    float occupiedLength = 0.0;
    for (uint interval = 0; interval < merged; interval++) occupiedLength += intervals[interval].y - intervals[interval].x;
    float stepLength = occupiedLength / float(pc.steps);
    float cosine = dot(direction, pc.sunDirectionAndIlluminance.xyz);
    float phase = lerp(cloudlyHG(cosine, pc.opticalParameters.y), cloudlyHG(cosine, pc.opticalParameters.z),
            pc.opticalParameters.w) * pc.lightingParameters.y;
    float transmittance = 1.0;
    float3 scattering = 0.0;
    uint sampleIndex = 0;
    // Each disjoint occupied interval receives its own final partial cell; empty space consumes no samples.
    for (uint interval = 0; interval < merged && transmittance > 0.001; interval++) {
        float intervalLength = intervals[interval].y - intervals[interval].x;
        uint cells = min(pc.steps + 1u, max(1u, uint(ceil(intervalLength / stepLength))));
        for (uint cell = 0; cell < cells && transmittance > 0.001; cell++) {
            float offset = float(cell) * stepLength;
            float at = intervals[interval].x + offset;
            float segment = max(0.0, min(stepLength, intervalLength - offset));
            float3 point = pc.cameraAndUnits.xyz + direction * (at + segment * cloudlySampleOffset(id.xy, sampleIndex++));
            float density = cloudlyDensity(point);
            if (density > 0.0) {
                float segmentT = exp(-min(density * pc.opticalParameters.x * segment, 80.0));
                float shadow = pc.sunDirectionAndIlluminance.w > 0.0
                        ? cloudlyShadow(point, pc.sunDirectionAndIlluminance.xyz) : 0.0;
                float illumination = pc.lightingParameters.x + pc.sunDirectionAndIlluminance.w * phase * shadow;
                scattering += transmittance * (1.0 - segmentT) * pc.lightingParameters.z * illumination;
                transmittance *= segmentT;
            }
        }
    }
    output[id.xy] = float4(background.rgb * transmittance + scattering * pc.lightingParameters.w, background.a);
}
