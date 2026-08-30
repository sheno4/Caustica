// Descriptor-heap-native roots shared by the world ray-tracing stages.
module bindings;

import caustica_resources;
import world_common;

// NVIDIA's descriptor-heap traversal path cannot consume an acceleration structure loaded directly
// from ResourceHeapEXT. The pipeline maps this conventional binding to the indexed heap slot named by
// WorldBindingRoots.resources.topLevelAS; images remain direct descriptor-heap accesses.
[[vk::binding(0, 0)]] RaytracingAccelerationStructure worldTopLevelAS;

public struct StorageImageIndex {
    public uint value;
};

public struct WorldResourceIndices {
    public AccelerationStructureIndex topLevelAS;
    public StorageImageIndex outputImage;
    public StorageImageIndex stablePlaneMetadataImage;
    public StorageImageIndex normalGuide;
    public StorageImageIndex albedoGuide;
    public StorageImageIndex depthGuide;
    public StorageImageIndex motionGuide;
    public StorageImageIndex specularAlbedoGuide;
    public StorageImageIndex specularMotionGuide;
};

/**
 * Reflected world-stage roots. Buffer/table members remain device addresses; images and acceleration
 * structures are indices in the renderer-bound resource descriptor heap.
 */
public struct WorldBindingRoots {
    public uint64_t worldPushAddress;
    public uint64_t compositionDataAddress;
    public uint64_t geometryTableAddress;
    public uint64_t pathQueueAddress;
    public WorldResourceIndices resources;
    public uint initialVolumeImplementation;
    public uint initialVolumeActive;
    public uint64_t initialVolumeBinding;
    public uint64_t initialVolumeInstance;
    public uint64_t neeAtStateAddress;
};

public RaytracingAccelerationStructure worldAccelerationStructure(WorldBindingRoots roots) {
    return worldTopLevelAS;
}

public RWTexture2D<float4> worldFloat4StorageImage(StorageImageIndex index) {
    RWTexture2D<float4> image = ResourceDescriptorHeap[NonUniformResourceIndex(index.value)];
    return image;
}

public RWTexture2D<float2> worldFloat2StorageImage(StorageImageIndex index) {
    RWTexture2D<float2> image = ResourceDescriptorHeap[NonUniformResourceIndex(index.value)];
    return image;
}

public RWTexture2D<float> worldFloatStorageImage(StorageImageIndex index) {
    RWTexture2D<float> image = ResourceDescriptorHeap[NonUniformResourceIndex(index.value)];
    return image;
}
