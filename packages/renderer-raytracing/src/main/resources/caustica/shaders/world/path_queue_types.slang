module path_queue_types;

// One restart ray per stable plane. Only the build/fill boundary uses device storage.
public struct PackedPathSegment {
    public float3 origin;
    public float hitDistance;
    public float3 direction;
    public float ior;
    public float3 throughput;
    public uint depth;
    public float3 absorption;
    public uint flags;
};
public static const uint PATH_ENDPOINT_VALID = 1u;
public static const uint PATH_ENDPOINT_CONTINUOUS = 2u;
public static const uint PATH_ENDPOINT_VOLUME = 4u;
