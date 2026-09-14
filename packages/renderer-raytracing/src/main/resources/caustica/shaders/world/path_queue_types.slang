module path_queue_types;
import world_common;

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

// Each scalar word owns one contiguous stream of all logical plane/pixel records.
public struct PathSegmentStorage {
    public uint64_t address;
    public uint recordCount;
    public uint loadWord(uint index, uint word) {
        return ((ConstPtr<uint>)address)[uint64_t(word) * uint64_t(recordCount) + uint64_t(index)];
    }
    public void storeWord(uint index, uint word, uint value) {
        ((DevicePtr<uint>)address)[uint64_t(word) * uint64_t(recordCount) + uint64_t(index)] = value;
    }
    public float3 loadOrigin(uint index) { return float3(asfloat(loadWord(index, 0u)), asfloat(loadWord(index, 1u)), asfloat(loadWord(index, 2u))); }
    public void storeOrigin(uint index, float3 value) {
        storeWord(index, 0u, asuint(value.x));
        storeWord(index, 1u, asuint(value.y));
        storeWord(index, 2u, asuint(value.z));
    }
    public float loadHitDistance(uint index) { return asfloat(loadWord(index, 3u)); }
    public void storeHitDistance(uint index, float value) {
        storeWord(index, 3u, asuint(value));
    }
    public float3 loadDirection(uint index) { return float3(asfloat(loadWord(index, 4u)), asfloat(loadWord(index, 5u)), asfloat(loadWord(index, 6u))); }
    public void storeDirection(uint index, float3 value) {
        storeWord(index, 4u, asuint(value.x));
        storeWord(index, 5u, asuint(value.y));
        storeWord(index, 6u, asuint(value.z));
    }
    public float loadIor(uint index) { return asfloat(loadWord(index, 7u)); }
    public void storeIor(uint index, float value) {
        storeWord(index, 7u, asuint(value));
    }
    public float3 loadThroughput(uint index) { return float3(asfloat(loadWord(index, 8u)), asfloat(loadWord(index, 9u)), asfloat(loadWord(index, 10u))); }
    public void storeThroughput(uint index, float3 value) {
        storeWord(index, 8u, asuint(value.x));
        storeWord(index, 9u, asuint(value.y));
        storeWord(index, 10u, asuint(value.z));
    }
    public uint loadDepth(uint index) { return loadWord(index, 11u); }
    public void storeDepth(uint index, uint value) {
        storeWord(index, 11u, value);
    }
    public float3 loadAbsorption(uint index) { return float3(asfloat(loadWord(index, 12u)), asfloat(loadWord(index, 13u)), asfloat(loadWord(index, 14u))); }
    public void storeAbsorption(uint index, float3 value) {
        storeWord(index, 12u, asuint(value.x));
        storeWord(index, 13u, asuint(value.y));
        storeWord(index, 14u, asuint(value.z));
    }
    public uint loadFlags(uint index) { return loadWord(index, 15u); }
    // Fill consumes the restart before replacing its origin words with the completed feedback reservoir.
    public uint2 loadFeedback(uint index) { return uint2(loadWord(index, 0u), loadWord(index, 1u)); }
    public void storeFeedback(uint index, uint2 value) {
        storeWord(index, 0u, value.x);
        storeWord(index, 1u, value.y);
    }
    public void storeFlags(uint index, uint value) {
        storeWord(index, 15u, value);
    }
    public PackedPathSegment load(uint index) {
        PackedPathSegment value = {};
        value.origin = loadOrigin(index);
        value.hitDistance = loadHitDistance(index);
        value.direction = loadDirection(index);
        value.ior = loadIor(index);
        value.throughput = loadThroughput(index);
        value.depth = loadDepth(index);
        value.absorption = loadAbsorption(index);
        value.flags = loadFlags(index);
        return value;
    }
    public void store(uint index, PackedPathSegment value) {
        storeOrigin(index, value.origin);
        storeHitDistance(index, value.hitDistance);
        storeDirection(index, value.direction);
        storeIor(index, value.ior);
        storeThroughput(index, value.throughput);
        storeDepth(index, value.depth);
        storeAbsorption(index, value.absorption);
        storeFlags(index, value.flags);
    }
};
public PathSegmentStorage pathSegmentStorage(uint64_t address, uint recordCount) {
    PathSegmentStorage storage;
    storage.address = address;
    storage.recordCount = recordCount;
    return storage;
}
