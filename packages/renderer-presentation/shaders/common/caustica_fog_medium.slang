module caustica_fog_medium;
import caustica_fog_types;
import caustica_volume;

public struct FogVolumeModel : IVolumeModel {};

/** Spatial medium evaluated in coordinates relative to the caller's origin. */
public struct FogMedium {
    // Float4 lattice: density multiplier, humidity, coverage, height relative to fieldOrigin.y.
    // X varies fastest, then Z, then Y. The caller retains the buffer for every shader invocation.
    public uint64_t fieldAddress;
    public float3 fieldOrigin;
    public float spacing;
    public int3 fieldSize;
    // The caller's absolute origin reduced modulo 4096 scene units.
    public float3 noiseOrigin;
    public float windPhase;
    // Extinction per scene unit; layer height is relative to the caller's origin.
    public float densityScale;
    public float layerHeight;
    public float heightFalloff;
};

public FogMedium mediumFromFogVolume(FogVolumeData volume) {
    FogMedium medium = {};
    medium.fieldAddress = volume.fieldAddress;
    medium.fieldOrigin = volume.fieldOrigin.xyz;
    medium.spacing = volume.fieldOrigin.w;
    medium.fieldSize = int3(volume.fieldSize.xyz);
    medium.noiseOrigin = volume.noiseOrigin.xyz;
    medium.windPhase = volume.noiseOrigin.w;
    medium.densityScale = volume.profile.x;
    medium.layerHeight = volume.profile.y;
    medium.heightFalloff = volume.profile.z;
    return medium;
}

float4 fogVoxel(FogMedium medium, int3 p) {
    int3 s = medium.fieldSize;
    p = clamp(p, int3(0), s - 1);
    float4* cells = (float4*)medium.fieldAddress;
    return cells[p.x + s.x * (p.z + s.z * p.y)];
}

public float4 sampleFogField(FogMedium medium, float3 relative) {
    float3 p = (relative - medium.fieldOrigin) / medium.spacing;
    float3 blend = saturate(p - floor(p));
    int3 cell = int3(floor(p));
    float4 v = lerp(lerp(lerp(fogVoxel(medium, cell), fogVoxel(medium, cell+int3(1,0,0)),blend.x),
                        lerp(fogVoxel(medium, cell+int3(0,0,1)),fogVoxel(medium, cell+int3(1,0,1)),blend.x),blend.z),
                   lerp(lerp(fogVoxel(medium, cell+int3(0,1,0)),fogVoxel(medium, cell+int3(1,1,0)),blend.x),
                        lerp(fogVoxel(medium, cell+int3(0,1,1)),fogVoxel(medium, cell+int3(1,1,1)),blend.x),blend.z),blend.y);
    // Coverage fades at the prepared field boundary rather than extending unknown terrain.
    float edge = min(min(p.x,medium.fieldSize.x-1-p.x),min(p.z,medium.fieldSize.z-1-p.z));
    v.z *= saturate(edge);
    return v;
}

public float fogDensity(FogMedium medium, float3 relative) {
    float4 v = sampleFogField(medium, relative);
    float aboveGround = relative.y - (v.w + medium.fieldOrigin.y);
    float layer = exp(-max(relative.y - medium.layerHeight,0.0) / medium.heightFalloff);
    float ground = exp(-max(aboveGround,0.0) / lerp(6.0,18.0,saturate(v.y)));
    // The coarse outdoor field excludes points below its terrain surface.
    float outdoor = smoothstep(-2.0,2.0,aboveGround);
    // Integer spatial and wind-phase harmonics remain periodic across both coordinate wraps.
    float3 n = (relative + medium.noiseOrigin) * (23.0 * 6.28318530718 / 4096.0)
             + float3(medium.windPhase * 192.0,0,medium.windPhase * 96.0);
    float noise = 0.65 + 0.35 * sin(n.x + sin(n.z)) * sin(n.z + n.y);
    return medium.densityScale * v.x * v.z * outdoor * (layer * 0.8 + ground * 0.2) * noise;
}
