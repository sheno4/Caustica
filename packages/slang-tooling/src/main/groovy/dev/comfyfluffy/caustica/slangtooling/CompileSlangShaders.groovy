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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject
import java.nio.file.Path
import java.nio.charset.StandardCharsets

/** Compiles selected Slang entry-point sources to validated Vulkan SPIR-V. */
abstract class CompileSlangShaders extends DefaultTask {
    private static final byte[] SPIRV_DEBUG_INFO_IMPORT =
            "NonSemantic.Shader.DebugInfo.100\u0000".getBytes(StandardCharsets.US_ASCII)

    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getSourceDirectory()

    /** Include roots supplied as directories or JARs containing {@code .slang} resources. */
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getIncludeDirectories()

    @Optional @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getAliasSourceDirectory()

    /** Alias filename to a source relative to aliasSourceDirectory, or to one resolved include root. */
    @Input abstract MapProperty<String, String> getModuleAliases()
    @Input abstract ListProperty<String> getSourcePatterns()
    /** Relative sources whose static bindings are covered by shader-create descriptor mappings. */
    @Input abstract ListProperty<String> getMappedDescriptorBindingSources()
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
        mappedDescriptorBindingSources.convention([])
        descriptorHeapNative.convention(false)
    }

    @TaskAction
    void compile() {
        File sourceRoot = sourceDirectory.get().asFile
        List<File> materializedIncludes = SlangIncludeRoots.materialize(
                includeDirectories.files, new File(temporaryDir, "jar-includes"))
        File aliases = new File(temporaryDir, "module-aliases")
        if (aliases.exists() && !aliases.deleteDir()) {
            throw new GradleException("failed to clear Slang module aliases ${aliases}")
        }
        aliases.mkdirs()
        moduleAliases.get().each { alias, source ->
            File input = resolveAliasSource(aliasSourceDirectory.isPresent()
                    ? aliasSourceDirectory.get().asFile : null, source, materializedIncludes)
            File output = new File(aliases, alias)
            output.parentFile.mkdirs()
            java.nio.file.Files.copy(input.toPath(), output.toPath())
        }

        List<File> includeRoots = ([aliases] + materializedIncludes.collectMany { File root ->
            SlangIncludeRoots.directoryTree(root)
        } + SlangIncludeRoots.directoryTree(sourceRoot))
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
                        "-warnings-as-errors", "all", "-warnings-disable", "41012", "-g2"]
                        + includes + ["-o", output.absolutePath])
            }
            execOps.exec { commandLine spirvVal.get(), "--target-env", vulkanTarget.get(), output.absolutePath }
            requireDebugInfo(output)
            if (descriptorHeapNative.get()) {
                String relativeSource = sourceRoot.toPath().relativize(source.toPath()).toString().replace('\\', '/')
                DescriptorHeapSpirv.validate(output, mappedDescriptorBindingSources.get().contains(relativeSource))
            }
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

    static File resolveAliasSource(File localRoot, String relativePath, List<File> materializedIncludes) {
        if (localRoot != null) {
            return new File(localRoot, relativePath)
        }
        List<File> matches = materializedIncludes.collect { new File(it, relativePath) }.findAll { it.isFile() }
        if (matches.size() != 1) {
            throw new GradleException("Slang alias source must resolve from exactly one include input: "
                    + "${relativePath} -> ${matches}")
        }
        matches[0]
    }

    static String outputBase(File root, File source) {
        String relative = root.toPath().relativize(source.toPath()).toString().replace('\\', '/')
        relative.endsWith(".slang") ? relative.substring(0, relative.length() - 6) : relative
    }

    static void requireDebugInfo(File spirv) {
        byte[] contents = spirv.bytes
        boolean found = false
        for (int offset = 0; offset <= contents.length - SPIRV_DEBUG_INFO_IMPORT.length && !found; offset++) {
            found = true
            for (int index = 0; index < SPIRV_DEBUG_INFO_IMPORT.length; index++) {
                if (contents[offset + index] != SPIRV_DEBUG_INFO_IMPORT[index]) {
                    found = false
                    break
                }
            }
        }
        if (!found) {
            throw new GradleException("compiled shader is missing SPIR-V debug information: ${spirv}")
        }
    }

}
