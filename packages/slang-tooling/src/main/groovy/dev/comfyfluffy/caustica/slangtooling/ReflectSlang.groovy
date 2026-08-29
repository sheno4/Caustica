package dev.comfyfluffy.caustica.slangtooling

import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

/** Compiles one Slang probe and publishes its raw reflection JSON plus validated SPIR-V. */
abstract class ReflectSlang extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getSourceFile()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getIncludeDirectories()
    @Input abstract Property<String> getSlangc()
    @Input abstract Property<String> getSpirvVal()
    @Input abstract Property<String> getSpirvProfile()
    @Input abstract Property<String> getVulkanTarget()
    @Input abstract Property<Boolean> getDescriptorHeapNative()
    @OutputFile abstract RegularFileProperty getReflectionJson()
    @OutputFile abstract RegularFileProperty getSpirv()

    @Inject abstract ExecOperations getExecOps()

    ReflectSlang() {
        descriptorHeapNative.convention(false)
    }

    @TaskAction
    void reflect() {
        File source = sourceFile.get().asFile
        List<File> directories = [source.parentFile]
        includeDirectories.files.findAll { it.isDirectory() }.each { root ->
            directories.add(root)
            root.eachFileRecurse(FileType.DIRECTORIES) { directories.add(it) }
        }
        directories = directories.unique().sort { it.absolutePath }
        File json = reflectionJson.get().asFile
        File output = spirv.get().asFile
        json.parentFile.mkdirs()
        output.parentFile.mkdirs()
        List<String> descriptorHeapOptions = descriptorHeapNative.get()
                ? ["-capability", "spvDescriptorHeapEXT", "-spirv-unified-descriptor-heap-stride"]
                : []
        execOps.exec {
            commandLine([slangc.get(), source.absolutePath]
                    + directories.collectMany { ["-I", it.absolutePath] }
                    + ["-target", "spirv"] + descriptorHeapOptions + ["-profile", spirvProfile.get(),
                       "-matrix-layout-column-major", "-warnings-as-errors", "all",
                       "-warnings-disable", "41012", "-reflection-json", json.absolutePath,
                       "-o", output.absolutePath])
        }
        execOps.exec { commandLine spirvVal.get(), "--target-env", vulkanTarget.get(), output.absolutePath }
        if (descriptorHeapNative.get()) DescriptorHeapSpirv.validate(output)
    }
}
