#pragma once

#include <stdint.h>

#ifdef _WIN32
#define NRDSHIM_EXPORT extern "C" __declspec(dllexport)
#else
#define NRDSHIM_EXPORT extern "C" __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
#define NRDSHIM_NOEXCEPT noexcept
#else
#define NRDSHIM_NOEXCEPT
#endif

struct NrdShimCreateDesc {
    uint64_t instance;
    uint64_t physicalDevice;
    uint64_t device;
    uint32_t graphicsQueueFamily;
    uint32_t queuedFrames;
    uint32_t width;
    uint32_t height;
    uint32_t method;
    uint32_t reserved;
};

struct NrdShimCommonSettings {
    float worldToView[16];
    float worldToViewPrevious[16];
    float viewToClip[16];
    float viewToClipPrevious[16];
    float jitterX;
    float jitterY;
    float previousJitterX;
    float previousJitterY;
    float motionScaleX;
    float motionScaleY;
    float motionScaleZ;
    float denoisingRange;
    float disocclusionThreshold;
    float alternateDisocclusionThreshold;
    float frameTimeMilliseconds;
    uint32_t frameIndex;
    uint32_t flags;
};

struct NrdShimImage {
    uint64_t image;
    uint32_t format;
    uint32_t layout;
};

struct NrdShimResources {
    NrdShimImage diffuseRadianceHitDistance;
    NrdShimImage specularRadianceHitDistance;
    NrdShimImage normalRoughness;
    NrdShimImage viewZ;
    NrdShimImage motion;
    NrdShimImage denoisedDiffuseRadianceHitDistance;
    NrdShimImage denoisedSpecularRadianceHitDistance;
    NrdShimImage disocclusionThresholdMix;
    NrdShimImage validationOutput;
};

NRDSHIM_EXPORT void* nrdshim_create(const NrdShimCreateDesc* description) NRDSHIM_NOEXCEPT;
NRDSHIM_EXPORT int32_t nrdshim_record(void* instance, uint64_t commandBuffer,
                                     const NrdShimCommonSettings* common,
                                     const NrdShimResources* resources) NRDSHIM_NOEXCEPT;
NRDSHIM_EXPORT void nrdshim_destroy(void* instance) NRDSHIM_NOEXCEPT;
NRDSHIM_EXPORT const char* nrdshim_last_error() NRDSHIM_NOEXCEPT;
