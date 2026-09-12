module path_queue_types;

// One restart ray per stable plane; build also stores queued paths in future endpoint slots.
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
// Delegated delta lobes are owned by another plane; the endpoint samples every remaining lobe.
public static const uint PATH_ENDPOINT_DELEGATED_REFLECTION = 2u;
public static const uint PATH_ENDPOINT_VOLUME = 4u;
public static const uint PATH_ENDPOINT_DELEGATED_TRANSMISSION = 8u;
