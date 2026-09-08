#include "nrd_shim.h"

#include <cstring>
#include <exception>
#include <memory>

#include "NRI.h"
#include "Extensions/NRIHelper.h"
#include "Extensions/NRIRayTracing.h"
#include "Extensions/NRIWrapperVK.h"
#include "NRD.h"
#include "NRDSettings.h"
#include "NRDIntegration.h"
#include "NRDIntegration.hpp"

static_assert(sizeof(NrdShimCreateDesc) == 48);
static_assert(sizeof(NrdShimCommonSettings) == 308);
static_assert(sizeof(NrdShimImage) == 16);
static_assert(sizeof(NrdShimResources) == 144);
static_assert(NRD_VERSION_MAJOR == 4 && NRD_VERSION_MINOR >= 15);

namespace {
thread_local char lastError[512]{};
constexpr nrd::Identifier kDenoiser = 0;

void setLastError(const char* message) noexcept {
    size_t index = 0;
    if (message) {
        while (message[index] && index + 1 < sizeof(lastError)) {
            lastError[index] = message[index];
            ++index;
        }
    }
    lastError[index] = '\0';
}

void clearLastError() noexcept {
    lastError[0] = '\0';
}

struct State {
    const uint16_t width;
    const uint16_t height;
    nrd::Integration integration;

    explicit State(const NrdShimCreateDesc& description)
        : width(static_cast<uint16_t>(description.width)), height(static_cast<uint16_t>(description.height)) {}

    bool initialize(const NrdShimCreateDesc& description) {
        const nrd::DenoiserDesc denoiser = {
            kDenoiser,
            description.method == 0 ? nrd::Denoiser::RELAX_DIFFUSE_SPECULAR
                                    : nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR
        };
        nrd::InstanceCreationDesc instanceCreation{};
        instanceCreation.denoisers = &denoiser;
        instanceCreation.denoisersNum = 1;

        nrd::IntegrationCreationDesc integrationCreation{};
        constexpr char integrationName[] = "Caustica NRD";
        std::memcpy(integrationCreation.name, integrationName, sizeof(integrationName));
        integrationCreation.resourceWidth = width;
        integrationCreation.resourceHeight = height;
        integrationCreation.queuedFrameNum = static_cast<uint8_t>(description.queuedFrames);
        integrationCreation.autoWaitForIdle = true;

        nri::QueueFamilyVKDesc queueFamily{};
        queueFamily.queueNum = 1;
        queueFamily.queueType = nri::QueueType::GRAPHICS;
        queueFamily.familyIndex = description.graphicsQueueFamily;

        nri::DeviceCreationVKDesc deviceCreation{};
        constexpr const char* enabledDeviceExtensions[] = {
            "VK_KHR_push_descriptor"
        };
        deviceCreation.vkExtensions.deviceExtensions = enabledDeviceExtensions;
        deviceCreation.vkExtensions.deviceExtensionNum = 1;
        deviceCreation.vkInstance = reinterpret_cast<void*>(description.instance);
        deviceCreation.vkPhysicalDevice = reinterpret_cast<void*>(description.physicalDevice);
        deviceCreation.vkDevice = reinterpret_cast<void*>(description.device);
        deviceCreation.queueFamilies = &queueFamily;
        deviceCreation.queueFamilyNum = 1;
        deviceCreation.minorVersion = 4;
        if (integration.RecreateVK(integrationCreation, instanceCreation, deviceCreation) != nrd::Result::SUCCESS) {
            return false;
        }

        if (description.method == 0) {
            nrd::RelaxSettings settings{};
            settings.enableAntiFirefly = true;
            settings.checkerboardMode = nrd::CheckerboardMode::OFF;
            settings.hitDistanceReconstructionMode = nrd::HitDistanceReconstructionMode::OFF;
            settings.diffusePrepassBlurRadius = 0.0f;
            settings.specularPrepassBlurRadius = 0.0f;
            settings.atrousIterationNum = 5;
            settings.lobeAngleFraction = 0.7f;
            settings.specularLobeAngleSlack = 0.2f;
            settings.depthThreshold = 0.004f;
            settings.diffuseMaxAccumulatedFrameNum = 25;
            settings.specularMaxAccumulatedFrameNum = 40;
            settings.diffuseMaxFastAccumulatedFrameNum = 5;
            settings.specularMaxFastAccumulatedFrameNum = 6;
            settings.antilagSettings.accelerationAmount = 0.55f;
            settings.antilagSettings.spatialSigmaScale = 2.5f;
            settings.antilagSettings.temporalSigmaScale = 0.3f;
            settings.antilagSettings.resetAmount = 0.5f;
            return integration.SetDenoiserSettings(kDenoiser, &settings) == nrd::Result::SUCCESS;
        }

        nrd::ReblurSettings settings{};
        settings.enableAntiFirefly = true;
        settings.hitDistanceParameters = {3.0f, 0.1f, 20.0f, -25.0f};
        settings.checkerboardMode = nrd::CheckerboardMode::OFF;
        settings.hitDistanceReconstructionMode = nrd::HitDistanceReconstructionMode::AREA_5X5;
        settings.maxAccumulatedFrameNum = 50;
        settings.diffusePrepassBlurRadius = 15.0f;
        settings.specularPrepassBlurRadius = 40.0f;
        return integration.SetDenoiserSettings(kDenoiser, &settings) == nrd::Result::SUCCESS;
    }
};

void copyMatrix(float (&target)[16], const float (&source)[16]) {
    std::memcpy(target, source, sizeof(target));
}

nrd::Resource resource(const NrdShimImage& image, bool storage) {
    nrd::Resource result{};
    result.vk.image = (decltype(result.vk.image))image.image;
    result.vk.format = (decltype(result.vk.format))image.format;
    const bool generalLayout = image.layout == 1; // VK_IMAGE_LAYOUT_GENERAL
    result.state = {
        storage ? nri::AccessBits::SHADER_RESOURCE_STORAGE : nri::AccessBits::SHADER_RESOURCE,
        generalLayout ? nri::Layout::GENERAL
                      : (storage ? nri::Layout::SHADER_RESOURCE_STORAGE : nri::Layout::SHADER_RESOURCE),
        nri::StageBits::COMPUTE_SHADER
    };
    return result;
}

bool validImage(const NrdShimImage& image, bool storage) {
    constexpr uint32_t general = 1; // VK_IMAGE_LAYOUT_GENERAL
    constexpr uint32_t readOnly = 1000314000; // VK_IMAGE_LAYOUT_READ_ONLY_OPTIMAL
    return image.image && image.format && (image.layout == general
            || (!storage && image.layout == readOnly));
}

void setRequired(nrd::ResourceSnapshot& snapshot, nrd::ResourceType slot,
                 const NrdShimImage& image, bool storage = false) {
    snapshot.SetResource(slot, resource(image, storage));
}

void setOptional(nrd::ResourceSnapshot& snapshot, nrd::ResourceType slot,
                 const NrdShimImage& image, bool storage = false) {
    if (image.image != 0) snapshot.SetResource(slot, resource(image, storage));
}

int32_t fail(const char* message) noexcept {
    setLastError(message);
    return -1;
}

void* create(const NrdShimCreateDesc* description) {
    if (!description || !description->instance || !description->physicalDevice || !description->device
            || !description->width || description->width > UINT16_MAX
            || !description->height || description->height > UINT16_MAX
            || !description->queuedFrames || description->queuedFrames > UINT8_MAX || description->method > 1) {
        fail("invalid create description");
        return nullptr;
    }
    auto state = std::make_unique<State>(*description);
    if (!state->initialize(*description)) {
        fail("NRD integration creation failed");
        return nullptr;
    }
    return state.release();
}

int32_t record(void* instance, uint64_t commandBuffer,
               const NrdShimCommonSettings* common,
               const NrdShimResources* resources) {
    if (!instance || !commandBuffer || !common || !resources) return fail("invalid frame description");
    if (!validImage(resources->diffuseRadianceHitDistance, false)
            || !validImage(resources->specularRadianceHitDistance, false)
            || !validImage(resources->normalRoughness, false)
            || !validImage(resources->viewZ, false)
            || !validImage(resources->motion, false)
            || !validImage(resources->denoisedDiffuseRadianceHitDistance, true)
            || !validImage(resources->denoisedSpecularRadianceHitDistance, true)
            || (resources->disocclusionThresholdMix.image
                    && !validImage(resources->disocclusionThresholdMix, false))
            || (resources->validationOutput.image && !validImage(resources->validationOutput, true))) {
        return fail("NRD images require defined formats and GENERAL or read-only layouts; outputs require GENERAL");
    }
    State& state = *static_cast<State*>(instance);

    nrd::CommonSettings settings{};
    copyMatrix(settings.worldToViewMatrix, common->worldToView);
    copyMatrix(settings.worldToViewMatrixPrev, common->worldToViewPrevious);
    copyMatrix(settings.viewToClipMatrix, common->viewToClip);
    copyMatrix(settings.viewToClipMatrixPrev, common->viewToClipPrevious);
    settings.cameraJitter[0] = common->jitterX;
    settings.cameraJitter[1] = common->jitterY;
    settings.cameraJitterPrev[0] = common->previousJitterX;
    settings.cameraJitterPrev[1] = common->previousJitterY;
    settings.motionVectorScale[0] = common->motionScaleX;
    settings.motionVectorScale[1] = common->motionScaleY;
    settings.motionVectorScale[2] = common->motionScaleZ;
    settings.denoisingRange = common->denoisingRange;
    settings.disocclusionThreshold = common->disocclusionThreshold;
    settings.disocclusionThresholdAlternate = common->alternateDisocclusionThreshold;
    settings.timeDeltaBetweenFrames = common->frameTimeMilliseconds;
    settings.frameIndex = common->frameIndex;
    settings.isMotionVectorInWorldSpace = (common->flags & 1u) != 0;
    settings.isDisocclusionThresholdMixAvailable = (common->flags & 2u) != 0;
    settings.enableValidation = (common->flags & 4u) != 0;
    settings.accumulationMode = (common->flags & 8u) != 0
            ? nrd::AccumulationMode::CLEAR_AND_RESTART : nrd::AccumulationMode::CONTINUE;
    // A backend owns one fixed extent, including all temporal history.
    settings.resourceSize[0] = settings.resourceSizePrev[0] = settings.rectSize[0] = settings.rectSizePrev[0] = state.width;
    settings.resourceSize[1] = settings.resourceSizePrev[1] = settings.rectSize[1] = settings.rectSizePrev[1] = state.height;

    state.integration.NewFrame();
    if (state.integration.SetCommonSettings(settings) != nrd::Result::SUCCESS) return fail("NRD common settings rejected");

    nrd::ResourceSnapshot snapshot{};
    snapshot.restoreInitialState = true;
    setRequired(snapshot, nrd::ResourceType::IN_DIFF_RADIANCE_HITDIST, resources->diffuseRadianceHitDistance);
    setRequired(snapshot, nrd::ResourceType::IN_SPEC_RADIANCE_HITDIST, resources->specularRadianceHitDistance);
    setRequired(snapshot, nrd::ResourceType::IN_NORMAL_ROUGHNESS, resources->normalRoughness);
    setRequired(snapshot, nrd::ResourceType::IN_VIEWZ, resources->viewZ);
    setRequired(snapshot, nrd::ResourceType::IN_MV, resources->motion);
    setRequired(snapshot, nrd::ResourceType::OUT_DIFF_RADIANCE_HITDIST,
                resources->denoisedDiffuseRadianceHitDistance, true);
    setRequired(snapshot, nrd::ResourceType::OUT_SPEC_RADIANCE_HITDIST,
                resources->denoisedSpecularRadianceHitDistance, true);
    setOptional(snapshot, nrd::ResourceType::IN_DISOCCLUSION_THRESHOLD_MIX,
                resources->disocclusionThresholdMix);
    setOptional(snapshot, nrd::ResourceType::OUT_VALIDATION, resources->validationOutput, true);

    nri::CommandBufferVKDesc command{};
    command.vkCommandBuffer = reinterpret_cast<void*>(commandBuffer);
    command.queueType = nri::QueueType::GRAPHICS;
    state.integration.DenoiseVK(&kDenoiser, 1, command, snapshot);
    return 0;
}
}

void* nrdshim_create(const NrdShimCreateDesc* description) noexcept {
    clearLastError();
    try {
        return create(description);
    } catch (const std::exception& failure) {
        setLastError(failure.what());
    } catch (...) {
        setLastError("unexpected exception while creating NRD");
    }
    return nullptr;
}

int32_t nrdshim_record(void* instance, uint64_t commandBuffer,
                       const NrdShimCommonSettings* common,
                       const NrdShimResources* resources) noexcept {
    clearLastError();
    try {
        return record(instance, commandBuffer, common, resources);
    } catch (const std::exception& failure) {
        setLastError(failure.what());
    } catch (...) {
        setLastError("unexpected exception while recording NRD");
    }
    return -1;
}

void nrdshim_destroy(void* instance) noexcept {
    clearLastError();
    try {
        delete static_cast<State*>(instance);
    } catch (const std::exception& failure) {
        setLastError(failure.what());
    } catch (...) {
        setLastError("unexpected exception while destroying NRD");
    }
}

const char* nrdshim_last_error() noexcept {
    return lastError;
}
