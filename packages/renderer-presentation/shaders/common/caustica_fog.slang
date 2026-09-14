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
float2 fogInterval(uint step, float maximumDistance, float surfaceDistance) {
    float2 t = float2(step, step + 1u) / float(pc.steps);
    float2 bounds = maximumDistance * t * t;
    // The distance grid is camera-relative; only the terminal interval depends on surface depth.
    return float2(bounds.x, max(0.0, min(bounds.y, surfaceDistance) - bounds.x));
}
public void runFog(uint3 id) {
    RWTexture2D<float4> output = ResourceDescriptorHeap[pc.destinationIndex];
    uint w,h; output.GetDimensions(w,h);
    if(any(id.xy >= uint2(w,h))) return;
    float2 uv = (float2(id.xy)+0.5)/float2(w,h);
    FogVolumeData medium = ((FogVolumeData*)pc.volumeAddress)[0];
    float distance = distanceAt(uv, medium.profile.w);
    uint visibilityCount = 8;
    uint firstStep = uint(pc.traceData.w);
    uint visibilityBase = (id.x+id.y*w)*visibilityCount;
    if(pc.mode == 2) {
        float3 direction = normalize(unproject(uv,0.0001));
        float4* rays = (float4*)pc.visibilityRaysAddress;
        for(uint j=0;j<visibilityCount;j++) {
            float2 interval = fogInterval(firstStep+j, medium.profile.w, distance);
            float3 p = direction*(interval.x+0.5*interval.y);
            float sigma = interval.y > 0.0 ? fogDensity(mediumFromFogVolume(medium), p+pc.mediumCamera.xyz) : 0.0;
            uint base = (visibilityBase+j)*3;
            rays[base] = float4(p+pc.tlasCamera.xyz,0.05);
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
        float3 visibility = 1.0;
        float4* visibilityResults = (float4*)pc.visibilityResultsAddress;
        float cosine = dot(direction,medium.lightDirection.xyz);
        float g = 0.45;
        float phase = (1.0-g*g)/(12.5663706*pow(1.0+g*g-2.0*g*cosine,1.5));
        for(uint i=firstStep;i<min(firstStep+visibilityCount,pc.steps);i++) {
            float2 interval = fogInterval(i, medium.profile.w, distance);
            if(interval.y <= 0.0) break;
            float segmentLength = interval.y;
            float3 p = direction*(interval.x+0.5*segmentLength);
            float sigma = fogDensity(mediumFromFogVolume(medium), p+pc.mediumCamera.xyz);
            visibility = visibilityResults[visibilityBase+i-firstStep].rgb;
            float segmentT = exp(-sigma*segmentLength);
            float3 source = (medium.ambientRadiance.xyz + medium.lightRadiance.xyz * (phase*visibility)) * pc.traceData.z;
            scattering += transmittance * (1.0-segmentT) * source * 0.95;
            transmittance *= segmentT;
        }
        output[id.xy] = float4(scattering,transmittance);
        RWTexture2D<float> distances = ResourceDescriptorHeap[pc.distanceIndex];
        distances[id.xy] = distance;
    } else {
        Texture2D<float4> fog = ResourceDescriptorHeap[pc.fogIndex];
        Texture2D<float> distances = ResourceDescriptorHeap[pc.distanceIndex];
        uint fw,fh; fog.GetDimensions(fw,fh);
        float2 f = uv*float2(fw,fh)-0.5;
        int2 base = int2(floor(f));
        float2 frac = f-floor(f);
        float4 sum = 0.0; float weight = 0.0;
        float nearestError = 1e30;
        float nearestDistance = 1.0;
        float4 nearestFog = float4(0,0,0,1);
        for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
            int2 p = clamp(base+int2(x,y),int2(0),int2(fw-1,fh-1));
            float d = distances.Load(int3(p,0));
            float spatial = (x == 0 ? 1-frac.x : frac.x)*(y == 0 ? 1-frac.y : frac.y);
            float4 sample = fog.Load(int3(p,0));
            float error = abs(d-distance);
            if(error < nearestError) { nearestError=error; nearestDistance=d; nearestFog=sample; }
            float k = spatial * exp(-error/max(1.0,distance*0.03));
            sum += sample*k; weight += k;
        }
        float4 volume;
        if(weight > 0.001) volume = sum/weight;
        else {
            // No low-resolution sample sees this surface. Match its path length instead of
            // blending unrelated foreground and background optical depths.
            float t = pow(max(nearestFog.a,1e-6),distance/max(nearestDistance,0.01));
            volume = float4(nearestFog.rgb*((1.0-t)/max(1.0-nearestFog.a,1e-6)),t);
        }
        if(pc.debug == 1) { output[id.xy] = float4(volume.aaa,1.0); return; }
        if(pc.debug == 2) { output[id.xy] = float4(volume.rgb,1.0); return; }
        Texture2D<float4> scene = ResourceDescriptorHeap[pc.sceneIndex];
        float4 color = scene.Load(int3(id.xy,0));
        output[id.xy] = float4(color.rgb*volume.a+volume.rgb,color.a);
    }
}
