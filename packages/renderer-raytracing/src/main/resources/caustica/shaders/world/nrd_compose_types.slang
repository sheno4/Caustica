module nrd_compose_types;

public struct NrdComposePush {
    public uint64_t stablePlaneAddress;
    public uint64_t frameAddress;
    public uint outputImageIndex;
    public uint diffuseSignalImageIndex;
    public uint specularSignalImageIndex;
    public uint normalRoughnessImageIndex;
    public uint viewZImageIndex;
    public uint motionImageIndex;
    public uint disocclusionMixImageIndex;
    public uint denoisedDiffuseImageIndex;
    public uint denoisedSpecularImageIndex;
    public uint stableRadianceImageIndex;
    public uint planeIndex;
    public uint signalEncoding; // 0: RELAX linear RGB, 1: REBLUR YCoCg
    public uint mode;           // 0: prepare one plane, 1: merge one plane
};

/** Camera-relative, non-jittered transforms shared by all three serialized NRD plane evaluations. */
public struct NrdPlaneFrame {
    public float4x4 currentViewProjection;
    public float4x4 previousViewProjection;
    public float3 cameraOffset;
    public float preExposure;
    public float3 cameraDelta;
    public float radianceScale;
    public uint width;
    public uint height;
};
