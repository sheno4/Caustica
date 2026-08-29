package dev.comfyfluffy.caustica.slangtooling

import org.gradle.api.provider.Property

abstract class SlangToolingExtension {
    abstract Property<String> getSlangc()
    abstract Property<String> getSpirvVal()
    abstract Property<String> getSpirvProfile()
    abstract Property<String> getVulkanTarget()
}
