module path_queue_types;

// Device-addressable continuation record shared by the primary producer, indirect consumer, and host
// reflection. Packing helpers live with the transport implementation; this module has no composition
// dependency so its exact std430 stride is always reflectable.
public struct PackedPathSegment {
    public float3 ro;
    public uint rd;
    public uint throughput;
    public uint currentExtinction;
    public uint outerExtinction;
    public uint mediumIors;
    public uint rayCone;
    public uint seed;
    public uint pathFlags;
    public uint nextRecord;
};

public static const uint PATH_NO_NEXT = 0xffffffffu;
public static const uint MAX_PATH_SEGMENTS = 2u;
