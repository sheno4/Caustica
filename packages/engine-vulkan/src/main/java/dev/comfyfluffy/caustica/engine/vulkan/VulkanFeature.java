package dev.comfyfluffy.caustica.engine.vulkan;

/** Feature booleans that the renderer enables on every logical device. */
public enum VulkanFeature {
    SHADER_INT64("shaderInt64"),
    SHADER_INT16("shaderInt16"),
    SHADER_FLOAT16("shaderFloat16"),
    DESCRIPTOR_BINDING_PARTIALLY_BOUND("descriptorBindingPartiallyBound"),
    SHADER_STORAGE_IMAGE_EXTENDED_FORMATS("shaderStorageImageExtendedFormats"),
    SHADER_STORAGE_IMAGE_READ_WITHOUT_FORMAT("shaderStorageImageReadWithoutFormat"),
    SHADER_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT("shaderStorageImageWriteWithoutFormat"),
    SHADER_DRAW_PARAMETERS("shaderDrawParameters"),
    SHADER_DEMOTE_TO_HELPER_INVOCATION("shaderDemoteToHelperInvocation"),
    BUFFER_DEVICE_ADDRESS("bufferDeviceAddress"),
    TIMELINE_SEMAPHORE("timelineSemaphore"),
    SYNCHRONIZATION_2("synchronization2"),
    DYNAMIC_RENDERING("dynamicRendering"),
    UNIFIED_IMAGE_LAYOUTS("unifiedImageLayouts"),
    DESCRIPTOR_HEAP("descriptorHeap"),
    SHADER_OBJECT("shaderObject"),
    SHADER_UNTYPED_POINTERS("shaderUntypedPointers"),
    ACCELERATION_STRUCTURE("accelerationStructure"),
    RAY_TRACING_PIPELINE("rayTracingPipeline"),
    RAY_QUERY("rayQuery"),
    RAY_TRACING_POSITION_FETCH("rayTracingPositionFetch");

    private final String vulkanName;

    VulkanFeature(String vulkanName) {
        this.vulkanName = vulkanName;
    }

    public String vulkanName() {
        return vulkanName;
    }
}
