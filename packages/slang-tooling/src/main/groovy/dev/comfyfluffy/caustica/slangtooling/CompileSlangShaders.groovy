package dev.comfyfluffy.caustica.slangtooling

import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject
import java.nio.file.Path

/** Compiles selected Slang entry-point sources to validated Vulkan SPIR-V. */
abstract class CompileSlangShaders extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getSourceDirectory()

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getIncludeDirectories()

    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getAliasSourceDirectory()

    /** Alias filename to source filename, both relative to aliasSourceDirectory. */
    @Input abstract MapProperty<String, String> getModuleAliases()
    @Input abstract ListProperty<String> getSourcePatterns()
    @Input abstract Property<String> getSlangc()
    @Input abstract Property<String> getSpirvVal()
    @Input abstract Property<String> getSpirvProfile()
    @Input abstract Property<String> getVulkanTarget()
    @Input abstract Property<Boolean> getDescriptorHeapNative()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @Inject abstract ExecOperations getExecOps()

    CompileSlangShaders() {
        sourcePatterns.convention(["**/*.rgen.slang", "**/*.rchit.slang", "**/*.rahit.slang",
                "**/*.rmiss.slang", "**/*.rcall.slang", "**/*.comp.slang",
                "**/*.vert.slang", "**/*.frag.slang"])
        moduleAliases.convention([:])
        descriptorHeapNative.convention(false)
    }

    @TaskAction
    void compile() {
        File sourceRoot = sourceDirectory.get().asFile
        File aliases = new File(temporaryDir, "module-aliases")
        if (aliases.exists() && !aliases.deleteDir()) {
            throw new GradleException("failed to clear Slang module aliases ${aliases}")
        }
        aliases.mkdirs()
        moduleAliases.get().each { alias, source ->
            File input = aliasSourceDirectory.file(source).get().asFile
            File output = new File(aliases, alias)
            output.parentFile.mkdirs()
            java.nio.file.Files.copy(input.toPath(), output.toPath())
        }

        List<File> includeRoots = ([aliases] + includeDirectories.files.collectMany { File root ->
            directoryTree(root)
        } + directoryTree(sourceRoot))
                .unique().sort { it.absolutePath }
        Set<File> shaderFiles = [] as LinkedHashSet
        sourcePatterns.get().each { pattern ->
            shaderFiles.addAll(sourceDirectory.get().asFileTree.matching { include pattern }.files)
        }
        List<File> ordered = shaderFiles.sort { it.absolutePath }
        def outputs = ordered.groupBy { outputBase(sourceRoot, it) + ".spv" }
                .findAll { ignored, files -> files.size() > 1 }
        if (!outputs.isEmpty()) throw new GradleException("duplicate shader output names: ${outputs}")

        File scratch = new File(temporaryDir, "spv")
        if (scratch.exists() && !scratch.deleteDir()) throw new GradleException("failed to clear ${scratch}")
        scratch.mkdirs()
        ordered.each { source ->
            File output = new File(scratch, outputBase(sourceRoot, source) + ".spv")
            output.parentFile.mkdirs()
            List<String> includes = ([source.parentFile] + includeRoots.findAll { it != source.parentFile })
                    .collectMany { ["-I", it.absolutePath] }
            List<String> descriptorHeapOptions = descriptorHeapNative.get()
                    ? ["-capability", "spvDescriptorHeapEXT", "-spirv-unified-descriptor-heap-stride"]
                    : []
            execOps.exec {
                commandLine([slangc.get(), source.absolutePath, "-target", "spirv"]
                        + descriptorHeapOptions + [
                        "-profile", spirvProfile.get(), "-matrix-layout-column-major",
                        "-warnings-as-errors", "all", "-warnings-disable", "41012", "-g"]
                        + includes + ["-o", output.absolutePath])
            }
            execOps.exec { commandLine spirvVal.get(), "--target-env", vulkanTarget.get(), output.absolutePath }
            if (descriptorHeapNative.get()) DescriptorHeapSpirv.validate(output)
        }

        File published = outputDirectory.get().asFile
        if (published.exists() && !published.deleteDir()) {
            throw new GradleException("failed to clear generated shaders under ${published}")
        }
        published.mkdirs()
        scratch.eachFileRecurse(FileType.FILES) { file ->
            Path relative = scratch.toPath().relativize(file.toPath())
            File destination = published.toPath().resolve(relative).toFile()
            destination.parentFile.mkdirs()
            java.nio.file.Files.move(file.toPath(), destination.toPath())
        }
    }

    static String outputBase(File root, File source) {
        String relative = root.toPath().relativize(source.toPath()).toString().replace('\\', '/')
        relative.endsWith(".slang") ? relative.substring(0, relative.length() - 6) : relative
    }

    static List<File> directoryTree(File root) {
        if (!root.isDirectory()) return []
        List<File> directories = [root]
        root.eachFileRecurse(FileType.DIRECTORIES) { directories.add(it) }
        directories
    }
}
