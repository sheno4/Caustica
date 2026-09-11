module stable_plane_types;

public static const uint STABLE_PLANE_COUNT = 3u;
public static const uint STABLE_PLANE_INVALID_BRANCH = 0xffffffffu;
public static const uint STABLE_PLANE_FLAG_VALID = 1u;
public static const uint STABLE_PLANE_FLAG_ENVIRONMENT = 2u;
public static const uint STABLE_PLANE_FLAG_DOMINANT = 4u;
public static const uint STABLE_PLANE_FLAG_TRANSMISSION = 8u;
// Flags bits 8 through 15 store the number of delta segments before the endpoint.
public static const uint STABLE_PLANE_DEPTH_SHIFT = 8u;
public static const uint STABLE_PLANE_DEPTH_MASK = 255u;

// Plane-major records keep every denoiser endpoint in a directly consumable world-space form.
public struct StablePlaneRecord {
    public float3 currentVirtualPosition;
    public float roughness;
    public float3 previousVirtualPosition;
    public float throughputLuminance;
    public float3 normal;
    public float diffuseHitDistance;
    public float3 diffuseBsdfEstimate;
    public float specularHitDistance;
    public float3 specularBsdfEstimate;
    public float3 noisyDiffuseRadiance;
    public uint flags;
    public float3 noisySpecularRadiance;
    public uint branchId;
};
