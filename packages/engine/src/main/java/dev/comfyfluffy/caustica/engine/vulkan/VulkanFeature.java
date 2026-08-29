package dev.comfyfluffy.caustica.engine.vulkan;

/** Feature booleans that the renderer enables on every logical device. */
public enum VulkanFeature {
    SHADER_INT64("shaderInt64"),
    SHADER_FLOAT16("shaderFloat16"),
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
