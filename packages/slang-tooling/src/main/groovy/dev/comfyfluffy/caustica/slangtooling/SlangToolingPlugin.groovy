package dev.comfyfluffy.caustica.slangtooling

import org.gradle.api.Plugin
import org.gradle.api.Project

final class SlangToolingPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        def tooling = project.extensions.create("slangTooling", SlangToolingExtension)
        tooling.slangc.convention(project.providers.provider { SlangToolResolver.slang("slangc") })
        tooling.spirvVal.convention(project.providers.provider { SlangToolResolver.vulkan("spirv-val") })
        tooling.spirvProfile.convention(project.providers.gradleProperty("slang_spirv_profile").orElse("spirv_1_6"))
        tooling.vulkanTarget.convention(project.providers.gradleProperty("slang_vulkan_target").orElse("vulkan1.4"))

        [GenerateShaderRecords, GenerateRtBindings, CompileSlangShaders, ReflectSlang].each { type ->
            project.tasks.withType(type).configureEach { task -> conventions(task, tooling) }
        }
    }

    private static void conventions(def task, SlangToolingExtension tooling) {
        task.slangc.convention(tooling.slangc)
        task.spirvVal.convention(tooling.spirvVal)
        task.spirvProfile.convention(tooling.spirvProfile)
        task.vulkanTarget.convention(tooling.vulkanTarget)
    }
}
