module caustica_fog;
import caustica_fog_types;
[[vk::push_constant]] FogPush pc;

float3 unproject(float2 uv, float depth) {
    float4 q = pc.clipX * (uv.x * 2.0 - 1.0) + pc.clipY * (uv.y * 2.0 - 1.0)
             + pc.clipZ * depth + pc.clipW;
    return q.xyz / q.w;
}
float distanceAt(float2 uv) {
    Texture2D<float> depth = ResourceDescriptorHeap[pc.depthIndex];
    uint w, h; depth.GetDimensions(w, h);
    uint2 pixel = min(uint2(uv * float2(w,h)),uint2(w-1,h-1));
    float d = depth.Load(int3(pixel,0));
    float2 traceUv = (float2(pixel)+0.5+float2(pc.fieldSize.w,pc.tlasCamera.w))/float2(w,h);
    return d <= 0.0 ? pc.medium.w : min(length(unproject(traceUv,d)), pc.medium.w);
}
float4 voxel(int3 p) {
    int3 s = int3(pc.fieldSize.xyz);
    p = clamp(p, int3(0), s - 1);
    float4* cells = (float4*)pc.fieldAddress;
    return cells[p.x + s.x * (p.z + s.z * p.y)];
}
float4 field(float3 world) {
    float3 p = (world - pc.fieldOrigin.xyz) / pc.fieldOrigin.w;
    float3 blend = saturate(p - floor(p));
    int3 cell = int3(floor(p));
    float4 v = lerp(lerp(lerp(voxel(cell), voxel(cell+int3(1,0,0)),blend.x),
                        lerp(voxel(cell+int3(0,0,1)),voxel(cell+int3(1,0,1)),blend.x),blend.z),
                   lerp(lerp(voxel(cell+int3(0,1,0)),voxel(cell+int3(1,1,0)),blend.x),
                        lerp(voxel(cell+int3(0,1,1)),voxel(cell+int3(1,1,1)),blend.x),blend.z),blend.y);
    // Coverage fades at the prepared field boundary rather than extending unknown terrain.
    float edge = min(min(p.x,pc.fieldSize.x-1-p.x),min(p.z,pc.fieldSize.z-1-p.z));
    v.z *= saturate(edge);
    return v;
}
float density(float3 relative) {
    float4 v = field(relative);
    float aboveGround = relative.y - (v.w + pc.fieldOrigin.y);
    float layer = exp(-max(relative.y - pc.medium.y,0.0) / pc.medium.z);
    float ground = exp(-max(aboveGround,0.0) / lerp(6.0,18.0,saturate(v.y)));
    // The coarse outdoor field excludes points below its terrain surface.
    float outdoor = smoothstep(-2.0,2.0,aboveGround);
    // Integer spatial and wind-phase harmonics remain periodic across both coordinate wraps.
    float3 n = (relative + pc.camera.xyz) * (23.0 * 6.28318530718 / 4096.0)
             + float3(pc.camera.w * 192.0,0,pc.camera.w * 96.0);
    float noise = 0.65 + 0.35 * sin(n.x + sin(n.z)) * sin(n.z + n.y);
    return pc.medium.x * v.x * v.z * outdoor * (layer * 0.8 + ground * 0.2) * noise;
}
float shadow(float3 p) {
    RaytracingAccelerationStructure tlas = ResourceDescriptorHeap[pc.tlasIndex];
    RayDesc ray;
    ray.Origin = p + pc.tlasCamera.xyz;
    ray.Direction = pc.lightDirection.xyz;
    ray.TMin = 0.05;
    ray.TMax = pc.medium.w;
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(tlas, RAY_FLAG_NONE, 0x01, ray);
    while(query.Proceed()) {}
    return query.CommittedStatus() == COMMITTED_NOTHING ? 1.0 : 0.0;
}
public void runFog(uint3 id) {
    RWTexture2D<float4> output = ResourceDescriptorHeap[pc.destinationIndex];
    uint w,h; output.GetDimensions(w,h);
    if(any(id.xy >= uint2(w,h))) return;
    float2 uv = (float2(id.xy)+0.5)/float2(w,h);
    float distance = distanceAt(uv);
    if(pc.mode == 0) {
        float3 direction = normalize(unproject(uv,0.0001));
        float stepLength = pc.medium.w / pc.steps;
        float transmittance = 1.0;
        float3 scattering = 0.0;
        float visibility = 1.0;
        float cosine = dot(direction,pc.lightDirection.xyz);
        float g = 0.45;
        float phase = (1.0-g*g)/(12.5663706*pow(1.0+g*g-2.0*g*cosine,1.5));
        for(uint i=0;i<pc.steps;i++) {
            float segmentStart = i*stepLength;
            if(segmentStart >= distance) break;
            float segmentLength = min(stepLength,distance-segmentStart);
            // Surface depth changes only the final segment; preceding samples keep their distances.
            float3 p = direction*(segmentStart+0.5*segmentLength);
            float sigma = density(p);
            if((i & 3) == 0) visibility = shadow(p);
            float segmentT = exp(-sigma*segmentLength);
            float3 source = pc.ambientRadiance.xyz + pc.lightRadiance.xyz * (phase*visibility);
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
        if(pc.lightDirection.w == 1.0) { output[id.xy] = float4(volume.aaa,1.0); return; }
        if(pc.lightDirection.w == 2.0) { output[id.xy] = float4(volume.rgb,1.0); return; }
        Texture2D<float4> scene = ResourceDescriptorHeap[pc.sceneIndex];
        float4 color = scene.Load(int3(id.xy,0));
        output[id.xy] = float4(color.rgb*volume.a+volume.rgb,color.a);
    }
}
