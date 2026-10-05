module caustica_cloudly_composite;
import caustica_cloudly_types;

[[vk::push_constant]] CloudlyCompositePush pc;

public void runCloudlyComposite(uint3 id) {
    RWTexture2D<float4> output = ResourceDescriptorHeap[pc.destinationIndex];
    uint width, height;
    output.GetDimensions(width, height);
    if (any(id.xy >= uint2(width, height))) return;
    Texture2D<float4> scene = ResourceDescriptorHeap[pc.sceneIndex];
    Texture2D<float4> clouds = ResourceDescriptorHeap[pc.cloudIndex];
    Texture2D<float> depth = ResourceDescriptorHeap[pc.depthIndex];
    int2 traceExtent = int2(pc.traceParameters.zw);
    float2 pixel = (float2(id.xy) + 0.5) / float2(width, height)
            * float2(traceExtent) - 0.5 - pc.traceParameters.xy;
    int2 base = int2(floor(pixel));
    float2 fraction = frac(pixel);
    int2 nearest = clamp(int2(floor(pixel + 0.5)), int2(0), traceExtent - 1);
    float reference = depth.Load(int3(nearest, 0));
    float4 medium = 0.0;
    float total = 0.0;
    for (uint y = 0; y < 2; y++) {
        for (uint x = 0; x < 2; x++) {
            int2 source = clamp(base + int2(x, y), int2(0), traceExtent - 1);
            float weight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                    * (y == 0 ? 1.0 - fraction.y : fraction.y);
            float candidate = depth.Load(int3(source, 0));
            // A sky sample cannot leak through a foreground surface silhouette.
            if ((reference == 0.0) != (candidate == 0.0)) weight = 0.0;
            else if (reference > 0.0) weight *= exp(-abs(candidate - reference)
                    / max(reference * 0.01, 1.0e-8));
            medium += weight * clouds.Load(int3(source, 0));
            total += weight;
        }
    }
    medium = total > 1.0e-6 ? medium / total : clouds.Load(int3(nearest, 0));
    float4 background = scene.Load(int3(id.xy, 0));
    output[id.xy] = float4(background.rgb * saturate(medium.a) + medium.rgb, background.a);
}
