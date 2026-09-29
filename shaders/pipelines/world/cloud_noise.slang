// SPDX-License-Identifier: GPL-3.0-only
// Alpha-Piscium noise resources and PCG hash by Luna5ama and the cited authors.
// See licenses/alpha-piscium and THIRD_PARTY_NOTICES.md.
module cloud_noise;
import world_common;

public static WorldPush cloudFrame = {};
public float pow2(float x) { return x*x; }
public float pow3(float x) { return x*x*x; }
public float pow5(float x) { return x*x*x*x*x; }
public float linearStep(float a, float b, float x) {
    float width = b-a;
    if (abs(width)<1e-6) return x>=b ? 1.0 : 0.0;
    return saturate((x-a)/width);
}

public uint3 hash_33_q3(uint3 p) {
    uint4 v = uint4(p, 0x85EBCA77u);
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.w; v.y += v.z * v.x; v.z += v.x * v.y; v.w += v.y * v.z;
    v ^= v >> 16u;
    v.x += v.y * v.w; v.y += v.z * v.x; v.z += v.x * v.y; v.w += v.y * v.z;
    return v.xyz;
}
public float3 hash_uintToFloat(uint3 v) { return asfloat(0x3f800000u | (v >> 9u)) - 1.0; }

float2 gradientHash(uint2 p) {
    uint3 v = uint3(p,0x85EBCA77u)*1664525u+1013904223u;
    v.x += v.y*v.z; v.y += v.z*v.x; v.z += v.x*v.y;
    v ^= v >> 16u;
    v.x += v.y*v.z; v.y += v.z*v.x; v.z += v.x*v.y;
    return (asfloat(0x3f800000u | (v.xy >> 9u))-1.0)*2.0-1.0;
}
// Inigo Quilez's gradient noise and analytic derivatives, as used by Alpha-Piscium.
float3 gradientValueDerivative(float2 p) {
    uint2 i = uint2(int2(floor(p)));
    float2 w = frac(p);
    float2 u = w*w*w*(w*(w*6.0-15.0)+10.0);
    float2 du = 30.0*w*w*(w*(w-2.0)+1.0);
    float2 ga = gradientHash(i), gb = gradientHash(i+uint2(1,0));
    float2 gc = gradientHash(i+uint2(0,1)), gd = gradientHash(i+uint2(1,1));
    float va = dot(ga,w), vb = dot(gb,w-float2(1,0));
    float vc = dot(gc,w-float2(0,1)), vd = dot(gd,w-1.0);
    float value = lerp(lerp(va,vb,u.x),lerp(vc,vd,u.x),u.y);
    float2 g = lerp(lerp(ga,gb,u.x),lerp(gc,gd,u.x),u.y);
    float2 d = lerp(float2(vb,vc)-va,vd-float2(vc,vb),u.yx);
    return float3(value,g+du*d);
}
public float3 cloudGradientFbm(float2 p, float frequency, float persistence, float lacunarity, float angle) {
    p *= frequency;
    float3 sum = 0.0;
    float amplitude = 1.0;
    float c = cos(angle), s = sin(angle);
    for (int i=0;i<3;i++) {
        sum += gradientValueDerivative(p)*amplitude;
        amplitude *= persistence;
        p = float2(c*p.x+s*p.y,-s*p.x+c*p.y)*lacunarity;
    }
    return sum;
}

public float3 cloudAp0To709(float3 color) {
    return mul(float3x3(2.5214008886,-1.1339957494,-0.3875618568,
                       -0.2762140616,1.3725955663,-0.0962823557,
                       -0.0153202001,-0.1529925618,1.1683871996),color);
}

uint cloudByte(uint offset) {
    uint word = ConstPtr<uint>(cloudFrame.cloudDataAddr)[offset >> 2u];
    return (word >> ((offset & 3u)*8u)) & 255u;
}
float cloudTexel2D(uint offset, int size, int2 p) {
    int2 q = p & (size-1);
    return float(cloudByte(offset + uint(q.x + q.y*size))) / 255.0;
}
public float cloudTexture2D(uint offset, int size, float2 uv) {
    float2 p = frac(uv)*float(size)-0.5;
    int2 i = int2(floor(p)); float2 f = frac(p);
    return lerp(lerp(cloudTexel2D(offset,size,i),cloudTexel2D(offset,size,i+int2(1,0)),f.x),
                lerp(cloudTexel2D(offset,size,i+int2(0,1)),cloudTexel2D(offset,size,i+int2(1,1)),f.x), f.y);
}
float3 cloudTexel3D(uint offset, int size, int3 p, bool curl) {
    int3 q = p & (size-1);
    uint index = uint(q.x + size*(q.y+size*q.z));
    if (!curl) return float3(float(cloudByte(offset+index))/255.0);
    uint packed = ConstPtr<uint>(cloudFrame.cloudDataAddr)[(offset>>2u)+index];
    int3 signedBytes = int3(int(packed<<24u)>>24, int(packed<<16u)>>24, int(packed<<8u)>>24);
    return max(float3(signedBytes)/127.0, -1.0);
}
public float3 cloudTexture3D(uint offset, int size, float3 uv, bool curl) {
    float3 p = frac(uv)*float(size)-0.5;
    int3 i = int3(floor(p)); float3 f = frac(p);
    float3 a = lerp(lerp(cloudTexel3D(offset,size,i,curl),cloudTexel3D(offset,size,i+int3(1,0,0),curl),f.x),
                    lerp(cloudTexel3D(offset,size,i+int3(0,1,0),curl),cloudTexel3D(offset,size,i+int3(1,1,0),curl),f.x),f.y);
    float3 b = lerp(lerp(cloudTexel3D(offset,size,i+int3(0,0,1),curl),cloudTexel3D(offset,size,i+int3(1,0,1),curl),f.x),
                    lerp(cloudTexel3D(offset,size,i+int3(0,1,1),curl),cloudTexel3D(offset,size,i+int3(1,1,1),curl),f.x),f.y);
    return lerp(a,b,f.z);
}
float4 phaseTexel(int x, int type) {
    uint address = (6356992u >> 2u) + uint(type*256+clamp(x,0,255))*2u;
    uint a = ConstPtr<uint>(cloudFrame.cloudDataAddr)[address];
    uint b = ConstPtr<uint>(cloudFrame.cloudDataAddr)[address+1u];
    return float4(f16tof32(a & 65535u), f16tof32(a>>16u), f16tof32(b & 65535u), f16tof32(b>>16u));
}
public float3 cloudPhase(float cosine, int type) {
    float theta = acos(clamp(cosine,-1.0,1.0));
    float u = saturate((0.672617934627 - 0.0713555761181*theta + 0.0299320735609*theta*theta)*pow(theta,0.264767018876));
    float x = u*256.0-0.5;
    float4 rgbm = lerp(phaseTexel(int(floor(x)),type), phaseTexel(int(floor(x))+1,type), frac(x));
    float3 ap0 = rgbm.rgb*rgbm.a;
    // AP0 to linear BT.709, matching Caustica's atmospheric working space.
    return max(cloudAp0To709(ap0),0.0);
}
