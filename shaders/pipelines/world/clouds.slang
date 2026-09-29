// SPDX-License-Identifier: GPL-3.0-only
// Alpha-Piscium cloud model adapted to Caustica's path segments and atmosphere.
// Copyright (c) Luna5ama and contributors. See THIRD_PARTY_NOTICES.md.
module clouds;
import world_common;
import sky;
import cloud_noise;
import cloud_density;

public struct CloudResult {
    public float3 radiance;
    public float3 transmittance;
};
static const float3 CLOUD_EXTINCTION = float3(0.1739606124,0.1814824514,0.1955467707);
static const float3 CLOUD_ASYMMETRY = float3(0.818549085,0.8704447182,0.948639718);
static const float3 CIRRUS_EXTINCTION = float3(5.898218117,6.217421688,6.741530656);

float3 cloudPosition(WorldPush frame, float3 rebased) {
    // Caustica's atmosphere maps 100 world blocks to one kilometre.
    float3 p = frame.cloudOrigin.xyz + (rebased-frame.camOffset)*0.01;
    p.xz += frame.cloudOrigin.w * frame.cloudQuality.z * float2(1.0,0.35);
    return p;
}

bool layerInterval(float3 origin, float3 dir, float lower, float upper,
                   float limit, out float begin, out float end) {
    begin = 0.0; end = max(limit,0.0);
    if (abs(dir.y) < 1e-6) return origin.y > lower && origin.y < upper && end > 0.0;
    float a = (lower-origin.y)/dir.y;
    float b = (upper-origin.y)/dir.y;
    begin = max(min(a,b),0.0);
    end = min(max(a,b),end);
    return end > begin;
}

float cirrusDensity(float3 p) {
    float2 curl = cloudGradientFbm(p.xz,0.01,0.8,2.0,2.399963229728653).yz;
    float coverage = cloudGradientFbm(p.xz+float2(-8.0,-4.0)+curl*16.0,0.04,0.6,1.9,SKY_PI*0.25).x;
    coverage = pow3(linearStep(-0.1,1.0,coverage));
    float density = cloudTexture2D(6291456u,256,(p.xz+0.114)*0.12+curl*2.5)*0.125;
    density += cloudTexture2D(6291456u,256,(p.xz+0.514)*0.08+curl*0.8)*0.25;
    density += cloudTexture2D(6291456u,256,p.xz*0.02+curl*0.4)*0.5;
    return coverage*density*cloudFrame.cloudQuality.y;
}

float3 lightColumn(float3 p, float3 dir, out float3 multiple) {
    float3 opticalDepth = 0.0;
    multiple = 0.0;
    float boundary = cloudBoundary(p,dir);
    float lengthKm = cloudFrame.cloudLayer.y;
    // Alpha-Piscium's ordered eight-sample, quadratic-distance light column.
    for (int i=0;i<8;i++) {
        float a = float(i)/8.0, b = float(i+1)/8.0;
        float ds = (b*b-a*a)*lengthKm;
        float distanceKm = a*a*lengthKm+0.5*ds;
        float rho = cumulusDensity(p+dir*distanceKm);
        float3 sigma = CLOUD_EXTINCTION*rho;
        float3 prefix = opticalDepth+sigma*(0.5*ds);
        float3 weight = sigma*ds*sigma/max(distanceKm,0.5*ds)*boundary;
        multiple += weight*exp(-(0.001+sqrt(0.003))*prefix)
                          *(1.0-exp(-max(1.0-CLOUD_ASYMMETRY,0.0)*prefix));
        opticalDepth += sigma*ds;
    }
    multiple = 1.0-exp(-max(multiple,0.0));
    return exp(-opticalDepth);
}

public float3 cloudShadow(WorldPush frame, float3 origin, float3 dir) {
    if (frame.cloudDataAddr == 0 || frame.cloudLayer.w <= 0.0) return float3(1.0);
    cloudFrame = frame;
    float3 p = cloudPosition(frame,origin);
    float start,end;
    float3 opticalDepth = 0.0;
    if (layerInterval(p,dir,frame.cloudLayer.x,frame.cloudLayer.x+frame.cloudLayer.y,200.0,start,end)) {
        float ds = (end-start)/16.0;
        for (int i=0;i<16;i++) {
            opticalDepth += CLOUD_EXTINCTION*cumulusDensity(p+dir*(start+(float(i)+0.5)*ds))*ds;
        }
    }
    if (layerInterval(p,dir,9.0,9.2,200.0,start,end)) {
        opticalDepth += CIRRUS_EXTINCTION*cirrusDensity(p+dir*((start+end)*0.5))*(end-start);
    }
    return exp(-opticalDepth);
}

void integrateLayer(float3 origin, float3 dir, float start, float end, bool cirrus,
                    SkyState state, Sampler2D transmittanceLut, float3 ambient,
                    float3 background, inout CloudResult result) {
    int steps = cirrus ? 8 : int(clamp(cloudFrame.cloudQuality.x,16.0,128.0));
    float ds = (end-start)/float(steps);
    float3 lightDir = state.sunIlluminance*max(state.sunDir.y+0.1,0.0)
                    >= state.moonIlluminance*max(state.moonDir.y+0.1,0.0) ? state.sunDir : state.moonDir;
    bool sun = dot(lightDir,state.sunDir) > 0.999;
    float illuminance = sun ? state.sunIlluminance : state.moonIlluminance;
    float angularRadius = sun ? state.sunAngularRadius : state.moonAngularRadius;
    float3 phase = cloudPhase(dot(dir,lightDir),cirrus ? 0 : 1);
    // Stable midpoint sampling avoids untracked stochastic motion in the DLSS sky guides.
    for (int i=0;i<steps;i++) {
        float distanceKm = start+(float(i)+0.5)*ds;
        float3 p = origin+dir*distanceKm;
        float density = cirrus ? cirrusDensity(p) : cumulusDensity(p);
        if (density <= 0.0) continue;
        float3 sigma = (cirrus ? CIRRUS_EXTINCTION : CLOUD_EXTINCTION)*density;
        float3 stepT = exp(-sigma*ds);
        float3 multiple = 0.0;
        float3 lightT = cirrus ? exp(-sigma*0.1/max(abs(lightDir.y),0.05)) : lightColumn(p,lightDir,multiple);
        float3 incident = illuminance*transmittanceToSpace(transmittanceLut,
                ATMOS_BOTTOM_KM+max(p.y,0.01), lightDir,angularRadius);
        float3 source = incident*(phase*lightT+multiple/(4.0*SKY_PI))+ambient;
        float3 aerialT = exp(-sampleMedium(max(origin.y,0.0)).extinction*distanceKm);
        source = source*aerialT+background*(1.0-aerialT);
        result.radiance += result.transmittance*(1.0-stepT)*source;
        result.transmittance *= stepT;
        if (max(result.transmittance.x,max(result.transmittance.y,result.transmittance.z))<0.001) break;
    }
}

public CloudResult integrateClouds(WorldPush frame, float3 origin, float3 dir, float limitBlocks,
                                   Sampler2D transmittanceLut, Sampler2D skyViewLut) {
    CloudResult result;
    result.radiance = 0.0; result.transmittance = 1.0;
    if (frame.cloudDataAddr == 0 || frame.cloudLayer.w <= 0.0) return result;
    cloudFrame = frame;
    float3 p = cloudPosition(frame,origin);
    float nearCu=0.0,farCu=0.0,nearCi=0.0,farCi=0.0;
    float limit = min(limitBlocks*0.01,200.0);
    bool cu = layerInterval(p,dir,frame.cloudLayer.x,frame.cloudLayer.x+frame.cloudLayer.y,limit,nearCu,farCu);
    bool ci = frame.cloudQuality.y>0.0 && layerInterval(p,dir,9.0,9.2,limit,nearCi,farCi);
    if (!cu && !ci) return result;
    SkyState state = skyState(frame);
    float3 ambient = skyViewLut.SampleLevel(skyViewLutUv(state.viewerRadiusKm,1.0,1.0,SKY_VIEW_BODY_SUN),0.0).rgb
                   + skyViewLut.SampleLevel(skyViewLutUv(state.viewerRadiusKm,1.0,1.0,SKY_VIEW_BODY_MOON),0.0).rgb
                   + state.airglowLuminance;
    float3 background = skyViewLut.SampleLevel(skyViewLutUv(state.viewerRadiusKm,dir.y,lightViewCosine(dir,state.sunDir),SKY_VIEW_BODY_SUN),0.0).rgb
                      + skyViewLut.SampleLevel(skyViewLutUv(state.viewerRadiusKm,dir.y,lightViewCosine(dir,state.moonDir),SKY_VIEW_BODY_MOON),0.0).rgb;
    // Front-to-back order also handles flight above either layer and downward reflections.
    if (ci && (!cu || nearCi<nearCu)) integrateLayer(p,dir,nearCi,farCi,true,state,transmittanceLut,ambient,background,result);
    if (cu) integrateLayer(p,dir,nearCu,farCu,false,state,transmittanceLut,ambient,background,result);
    if (ci && cu && nearCi>=nearCu) integrateLayer(p,dir,nearCi,farCi,true,state,transmittanceLut,ambient,background,result);
    return result;
}
