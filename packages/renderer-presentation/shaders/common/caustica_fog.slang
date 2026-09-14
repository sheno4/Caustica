module caustica_fog;
import caustica_fog_types;
import caustica_fog_medium;
[[vk::push_constant]] FogPush pc;

float3 unproject(float2 uv, float depth) {
    float4 q = pc.clipX * (uv.x * 2.0 - 1.0) + pc.clipY * (uv.y * 2.0 - 1.0)
             + pc.clipZ * depth + pc.clipW;
    return q.xyz / q.w;
}
float distanceAt(float2 uv, float maximumDistance) {
    Texture2D<float> depth = ResourceDescriptorHeap[pc.depthIndex];
    uint w, h; depth.GetDimensions(w, h);
    // The depth samples include trace jitter, whereas the post image uses an unjittered grid.
    float2 pixel = uv * float2(w,h) - 0.5 - pc.traceData.xy;
    int2 base = int2(floor(pixel));
    float2 blend = pixel - floor(pixel);
    int2 nearest = clamp(int2(floor(pixel+0.5)),int2(0),int2(w-1,h-1));
    float reference = depth.Load(int3(nearest,0));
    float sum = 0.0;
    float weight = 0.0;
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        int2 p = clamp(base+int2(x,y),int2(0),int2(w-1,h-1));
        float d = depth.Load(int3(p,0));
        // Projected depth is affine on a plane; reject depth discontinuities before interpolation.
        if(abs(d-reference) > max(reference*0.05,1e-8)) continue;
        float k = (x == 0 ? 1-blend.x : blend.x)*(y == 0 ? 1-blend.y : blend.y);
        sum += d*k;
        weight += k;
    }
    float d = sum/weight;
    return d <= 0.0 ? maximumDistance : min(length(unproject(uv,d)), maximumDistance);
}
float2 fogInterval(uint step, float maximumDistance) {
    float2 t = float2(step, step + 1u) / float(pc.steps);
    float2 bounds = maximumDistance * t * t;
    return float2(bounds.x, bounds.y - bounds.x);
}
float intervalDensity(FogVolumeData medium, float3 direction, float2 interval) {
    // Four-point Gauss integration resolves density variation inside distant, wide cells.
    const float4 nodes = float4(0.0694318442, 0.3300094782, 0.6699905218, 0.9305681558);
    const float4 weights = float4(0.1739274226, 0.3260725774, 0.3260725774, 0.1739274226);
    float density = 0.0;
    for (uint i = 0; i < 4; i++) {
        float3 p = direction * (interval.x + nodes[i] * interval.y) + pc.mediumCamera.xyz;
        density += weights[i] * fogDensity(mediumFromFogVolume(medium), p);
    }
    return density;
}
float fogSamplePhase(uint2 pixel, uint step) {
    uint hash = pixel.x * 0x9e3779b9u ^ pixel.y * 0x85ebca6bu ^ step * 0xc2b2ae35u
              ^ asuint(pc.mediumCamera.w) * 0x27d4eb2du;
    hash ^= hash >> 16; hash *= 0x7feb352du; hash ^= hash >> 15; hash *= 0x846ca68bu; hash ^= hash >> 16;
    return (float(hash >> 8) + 0.5) / 16777216.0;
}
float4 fogPrefixAt(uint2 pixel, uint width, float distance, float maximumDistance) {
    float cell = sqrt(saturate(distance / maximumDistance)) * float(pc.steps);
    uint step = min(uint(cell), pc.steps - 1u);
    float2 interval = fogInterval(step, maximumDistance);
    float fraction = saturate((distance - interval.x) / interval.y);
    float4* prefix = (float4*)pc.prefixAddress;
    uint base = (pixel.x + pixel.y * width) * (pc.steps + 1u) + step;
    float4 before = prefix[base], after = prefix[base + 1u];
    if (fraction <= 0.0) return before;
    if (fraction >= 1.0) return after;
    // Integrate the partial cell with the same homogeneous coefficients as its stored endpoints.
    if (before.a <= 0.0) return before;
    float segmentT = saturate(after.a / before.a);
    float partialT = pow(segmentT, fraction);
    float scatteringFraction = 1.0 - segmentT > 1e-5 ? (1.0 - partialT) / (1.0 - segmentT) : fraction;
    return float4(lerp(before.rgb, after.rgb, scatteringFraction), before.a * partialT);
}
float4 previousVisibility(float3 center, uint2 extent, float maximumDistance, float3 lightDirection) {
    float4 q = pc.previousClipX * center.x + pc.previousClipY * center.y
             + pc.previousClipZ * center.z + pc.previousClipW;
    if (q.w <= 0.0) return 0.0;
    float4* history = (float4*)pc.previousHistoryAddress;
    float4 header = history[0];
    if (asuint(header.w) != asuint(pc.mediumCamera.w) - 1u
        || dot(header.xyz, lightDirection) < 0.9999) return 0.0;
    float2 uv = q.xy / q.w * 0.5 + 0.5;
    if (any(uv < 0.0) || any(uv > 1.0)) return 0.0;
    float4 previousPoint = history[1] * q.x + history[2] * q.y + history[3] * q.z + history[4] * q.w;
    float distance = length(previousPoint.xyz / previousPoint.w);
    if (distance >= maximumDistance) return 0.0;
    float depthCell = sqrt(max(0.0, distance * float(pc.steps * pc.steps) / maximumDistance - 0.25)) - 0.5;
    float3 grid = float3(uv * float2(extent) - 0.5, depthCell);
    int3 base = int3(floor(grid));
    float3 blend = grid - floor(grid);
    float4 result = 0.0;
    for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        int3 cell = clamp(base + int3(x,y,z), int3(0), int3(extent - 1u, pc.steps - 1u));
        float weight = (x == 0 ? 1.0-blend.x : blend.x) * (y == 0 ? 1.0-blend.y : blend.y)
                     * (z == 0 ? 1.0-blend.z : blend.z);
        float4 sample = history[5u + (uint(cell.x) + uint(cell.y) * extent.x) * pc.steps + uint(cell.z)];
        result.rgb += sample.rgb * (sample.a * weight);
        result.a += sample.a * weight;
    }
    if (result.a > 0.0) result.rgb /= result.a;
    return result;
}
public void runFog(uint3 id) {
    RWTexture2D<float4> output = ResourceDescriptorHeap[pc.destinationIndex];
    uint w,h; output.GetDimensions(w,h);
    if(any(id.xy >= uint2(w,h))) return;
    float2 uv = (float2(id.xy)+0.5)/float2(w,h);
    FogVolumeData medium = ((FogVolumeData*)pc.volumeAddress)[0];
    uint visibilityCount = 8;
    uint firstStep = uint(pc.traceData.w);
    uint visibilityBase = (id.x+id.y*w)*visibilityCount;
    if(pc.mode == 2) {
        float3 direction = normalize(unproject(uv,0.0001));
        float4* rays = (float4*)pc.visibilityRaysAddress;
        float4* prefix = (float4*)pc.prefixAddress;
        uint prefixBase = (id.x + id.y * w) * (pc.steps + 1u);
        for(uint j=0;j<visibilityCount;j++) {
            uint step = firstStep+j;
            float2 interval = fogInterval(step, medium.profile.w);
            float3 p = direction*(interval.x+fogSamplePhase(id.xy,step)*interval.y);
            float sigma = step < pc.steps ? intervalDensity(medium, direction, interval) : 0.0;
            // The integration dispatch consumes this density before replacing it with cumulative transport.
            if (step < pc.steps) prefix[prefixBase+step+1u] = float4(0,0,0,sigma);
            uint base = (visibilityBase+j)*3;
            rays[base] = float4(p+pc.tlasCamera.xyz,pc.tlasCamera.w);
            rays[base+1] = float4(medium.lightDirection.xyz,sigma > 0.0 ? medium.profile.w : 0.0);
            rays[base+2] = float4(0,0,0,1);
        }
        return;
    }
    if(pc.mode == 0) {
        float3 direction = normalize(unproject(uv,0.0001));
        float4 accumulated = firstStep == 0 ? float4(0,0,0,1) : output[id.xy];
        float transmittance = accumulated.a;
        float3 scattering = accumulated.rgb;
        float4* prefix = (float4*)pc.prefixAddress;
        float4* history = (float4*)pc.historyAddress;
        if (firstStep == 0u && all(id.xy == uint2(0))) {
            history[0] = float4(medium.lightDirection.xyz, pc.mediumCamera.w);
            history[1] = pc.clipX; history[2] = pc.clipY; history[3] = pc.clipZ; history[4] = pc.clipW;
        }
        uint prefixBase = (id.x + id.y * w) * (pc.steps + 1u);
        if (firstStep == 0u) prefix[prefixBase] = accumulated;
        float3 visibility = 1.0;
        float4* visibilityResults = (float4*)pc.visibilityResultsAddress;
        float cosine = dot(direction,medium.lightDirection.xyz);
        float g = 0.45;
        float phase = (1.0-g*g)/(12.5663706*pow(1.0+g*g-2.0*g*cosine,1.5));
        for(uint i=firstStep;i<min(firstStep+visibilityCount,pc.steps);i++) {
            float2 interval = fogInterval(i, medium.profile.w);
            float segmentLength = interval.y;
            float3 p = direction*(interval.x+0.5*segmentLength);
            float sigma = prefix[prefixBase+i+1u].a;
            if (sigma == 0.0) {
                history[5u + (id.x + id.y * w) * pc.steps + i] = 0.0;
                prefix[prefixBase+i+1u] = float4(scattering,transmittance);
                continue;
            }
            visibility = visibilityResults[visibilityBase+i-firstStep].rgb;
            float4 previous = previousVisibility(p, uint2(w,h), medium.profile.w, medium.lightDirection.xyz);
            float3 minimumVisibility = visibility, maximumVisibility = visibility;
            float4* rays = (float4*)pc.visibilityRaysAddress;
            for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                uint2 neighbor = uint2(clamp(int2(id.xy) + int2(x,y), int2(0), int2(w-1,h-1)));
                uint sampleIndex = (neighbor.x + neighbor.y * w) * visibilityCount + i - firstStep;
                if (rays[sampleIndex * 3u + 1u].w <= rays[sampleIndex * 3u].w) continue;
                float3 currentVisibility = visibilityResults[sampleIndex].rgb;
                minimumVisibility = min(minimumVisibility, currentVisibility);
                maximumVisibility = max(maximumVisibility, currentVisibility);
            }
            // Current local visibility bounds retire stale shadows when an occluder changes.
            previous.rgb = clamp(previous.rgb, minimumVisibility, maximumVisibility);
            float confidence = min(previous.a + 1.0, 8.0);
            visibility = lerp(previous.rgb, visibility, 1.0 / confidence);
            history[5u + (id.x + id.y * w) * pc.steps + i] = float4(visibility, confidence);
            float segmentT = exp(-sigma*segmentLength);
            float3 source = (medium.ambientRadiance.xyz + medium.lightRadiance.xyz * (phase*visibility)) * pc.traceData.z;
            scattering += transmittance * (1.0-segmentT) * source * 0.95;
            transmittance *= segmentT;
            prefix[prefixBase+i+1u] = float4(scattering,transmittance);
        }
        output[id.xy] = float4(scattering,transmittance);
    } else {
        Texture2D<float4> fog = ResourceDescriptorHeap[pc.fogIndex];
        uint fw,fh; fog.GetDimensions(fw,fh);
        float2 f = uv*float2(fw,fh)-0.5;
        int2 base = int2(floor(f));
        float2 frac = f-floor(f);
        float distance = distanceAt(uv, medium.profile.w);
        float4 volume = 0.0;
        for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
            int2 p = clamp(base+int2(x,y),int2(0),int2(fw-1,fh-1));
            float spatial = (x == 0 ? 1-frac.x : frac.x)*(y == 0 ? 1-frac.y : frac.y);
            volume += fogPrefixAt(uint2(p), fw, distance, medium.profile.w) * spatial;
        }
        if(pc.debug == 1) { output[id.xy] = float4(volume.aaa,1.0); return; }
        if(pc.debug == 2) { output[id.xy] = float4(volume.rgb,1.0); return; }
        Texture2D<float4> scene = ResourceDescriptorHeap[pc.sceneIndex];
        float4 color = scene.Load(int3(id.xy,0));
        output[id.xy] = float4(color.rgb*volume.a+volume.rgb,color.a);
    }
}
