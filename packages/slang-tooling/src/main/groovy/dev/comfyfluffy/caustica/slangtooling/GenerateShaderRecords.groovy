package dev.comfyfluffy.caustica.slangtooling

import groovy.json.JsonSlurper
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

@DisableCachingByDefault(because = 'Uses locally installed shader compiler and validator binaries')
abstract class GenerateShaderRecords extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getShaderRoot()

    /** Additional include roots supplied as directories or JARs containing {@code .slang} resources. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getIncludeDirectories()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getProbeSource()

    @Input abstract Property<String> getSlangc()
    @Input abstract Property<String> getSpirvVal()
    @Input abstract Property<String> getSpirvProfile()
    @Input abstract Property<String> getVulkanTarget()
    @Input abstract ListProperty<String> getRecordSpecs()
    @OutputDirectory abstract DirectoryProperty getOutDir()

    @Inject abstract ExecOperations getExecOps()

    private static final Set<String> RECORD_KINDS = ["buffer", "push"] as Set
    private static final String IDENTIFIER = /[A-Za-z_$][A-Za-z0-9_$]*/
    private static final String PACKAGE_NAME = /[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*/

    /**
     * Parses {@code kind|probe|struct|package|class|reader} task inputs. Buffer records may enable
     * scalar readers; push-constant records always use {@code false}.
     */
    static List<Map<String, Object>> parseRecordSpecs(List<String> specs) {
        def parsed = specs.collect { raw ->
            def fields = raw.split(/\|/, -1) as List<String>
            if (fields.size() != 6) {
                throw new GradleException("malformed shader record spec '${raw}': expected kind|probe|struct|package|class|reader")
            }
            def (kind, probeName, structName, packageName, className, readerText) = fields
            if (!RECORD_KINDS.contains(kind)) {
                throw new GradleException("unknown shader record kind '${kind}' in '${raw}'")
            }
            if (!(probeName ==~ IDENTIFIER) || !(structName ==~ IDENTIFIER)
                    || !(packageName ==~ PACKAGE_NAME) || !(className ==~ IDENTIFIER)) {
                throw new GradleException("malformed shader record spec '${raw}': invalid identifier")
            }
            if (!(readerText in ["true", "false"])) {
                throw new GradleException("malformed shader record spec '${raw}': reader must be true or false")
            }
            boolean emitReader = Boolean.parseBoolean(readerText)
            if (kind == "push" && emitReader) {
                throw new GradleException("malformed shader record spec '${raw}': push-constant readers are not supported")
            }
            [kind: kind, probeName: probeName, structName: structName, packageName: packageName,
             className: className, emitReader: emitReader] as Map<String, Object>
        }
        def duplicateProbe = parsed.groupBy { it.probeName }.find { it.value.size() > 1 }?.key
        if (duplicateProbe != null) {
            throw new GradleException("duplicate shader record probe '${duplicateProbe}'")
        }
        def duplicateOutput = parsed.groupBy { "${it.packageName}.${it.className}" }
                .find { it.value.size() > 1 }?.key
        if (duplicateOutput != null) {
            throw new GradleException("duplicate generated shader record '${duplicateOutput}'")
        }
        parsed
    }

    // Gradle decorates this abstract task with a generated subclass, so reflection helpers called
    // from Groovy closures must remain visible to dynamic dispatch.
    static Map extractPushConstantType(Object reflection, String probeName, String structName) {
        def pushParameter = reflection.parameters.find { it.name == probeName }
        if (pushParameter?.type?.elementType?.name != structName) {
            throw new GradleException("Slang reflection omitted or misshaped ${probeName} (expected ${structName})")
        }
        pushParameter.type.elementType as Map
    }

    static int extractPushConstantByteSize(Object reflection, String probeName) {
        def pushParameter = reflection.parameters.find { it.name == probeName }
        pushParameter.type.elementVarLayout.binding.size as int
    }

    // NOT private: see the comment on extractPushConstantType -- same closure-dispatch issue.
    static Map extractBufferProbeArray(Object reflection, String probeName, String structName) {
        def parameter = reflection.parameters.find { it.name == probeName }
        def probeArray = parameter?.type?.resultType?.fields?.find { it.name == "values" }
        if (probeArray?.type?.kind != "array" || probeArray.type.elementType?.name != structName) {
            throw new GradleException("Slang reflection omitted or misshaped ${probeName} (expected ${structName})")
        }
        probeArray.type as Map
    }

    @TaskAction
    void generate() {
        def specs = parseRecordSpecs(recordSpecs.get())
        def reflectionFile = new File(temporaryDir, "shader-records-reflection.json")
        def probeSpv = new File(temporaryDir, "shader-layout-probe.spv")
        def externalRoots = SlangIncludeRoots.materialize(
                includeDirectories.files, new File(temporaryDir, "jar-includes"))
        def includeArgs = (shaderRoot.get().asFileTree.matching { include "**/*.slang" }.files
                .collect { it.parentFile }.unique().sort { it.absolutePath }
                + externalRoots.collectMany { SlangIncludeRoots.directoryTree(it) })
                .unique().sort { it.absolutePath }.collectMany { ["-I", it.absolutePath] }
        execOps.exec {
            commandLine([slangc.get(), probeSource.get().asFile.absolutePath] + includeArgs +
                    [
                    "-target", "spirv", "-profile", spirvProfile.get(), "-matrix-layout-column-major",
                    "-warnings-as-errors", "all", "-warnings-disable", "41012",
                    "-reflection-json", reflectionFile.absolutePath, "-o", probeSpv.absolutePath])
        }
        execOps.exec {
            commandLine spirvVal.get(), "--target-env", vulkanTarget.get(), probeSpv.absolutePath
        }

        def reflection = new JsonSlurper().parse(reflectionFile)

        def generatedRoot = outDir.get().asFile
        if (generatedRoot.exists() && !generatedRoot.deleteDir()) {
            throw new GradleException("failed to clear generated shader record sources under ${generatedRoot}")
        }
        specs.each { spec ->
            String probeName = spec.probeName as String
            String structName = spec.structName as String
            String packageName = spec.packageName as String
            String className = spec.className as String
            Map type
            int byteSize
            if (spec.kind == "buffer") {
                Map probeArray = extractBufferProbeArray(reflection, probeName, structName)
                type = probeArray.elementType as Map
                byteSize = probeArray.uniformStride as int
            } else {
                type = extractPushConstantType(reflection, probeName, structName)
                byteSize = extractPushConstantByteSize(reflection, probeName)
            }
            def packageDir = new File(generatedRoot, packageName.replace('.', '/'))
            packageDir.mkdirs()
            new File(packageDir, "${className}.java").setText(
                    ShaderRecordSource.generateJava(type, byteSize, packageName, className, spec.emitReader as boolean), "UTF-8")
        }
    }
}
