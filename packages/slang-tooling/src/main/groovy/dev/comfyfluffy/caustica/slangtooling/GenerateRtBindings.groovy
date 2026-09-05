package dev.comfyfluffy.caustica.slangtooling

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
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

/** Generates the Java view of Vulkan descriptor locations from Slang reflection. */
abstract class GenerateRtBindings extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getShaderRoot()

    /** Additional include roots supplied as directories or JARs containing {@code .slang} resources. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getIncludeDirectories()

    @Input abstract Property<String> getSlangc()
    @Input abstract Property<String> getSpirvVal()
    @Input abstract Property<String> getSpirvProfile()
    @Input abstract Property<String> getVulkanTarget()
    @Input abstract Property<String> getJavaPackage()
    @OutputDirectory abstract DirectoryProperty getOutDir()

    @Inject abstract ExecOperations getExecOps()

    private static final List<Map> PIPELINES = [
            [prefix: "WORLD", source: "layout/build_reflection.rgen.slang",
             pushParameter: "worldBindings", pushType: "WorldBindingRoots",
             mappedDescriptors: [worldTopLevelAS: [index: 0, set: 0]],
             addresses: [PUSH: "worldPushAddress", COMPOSITION_DATA: "compositionDataAddress",
                         GEOMETRY_TABLE: "geometryTableAddress", PATH_QUEUE: "pathQueueAddress",
                         STABLE_PLANE_BUFFER: "stablePlaneBufferAddress",
                         NEE_AT_STATE: "neeAtStateAddress"],
             words: [INITIAL_VOLUME_BINDING: "initialVolumeBinding",
                     INITIAL_VOLUME_INSTANCE: "initialVolumeInstance"],
             scalars: [INITIAL_VOLUME_IMPLEMENTATION: "initialVolumeImplementation",
                       INITIAL_VOLUME_ACTIVE: "initialVolumeActive",
                       NRD_SIGNAL_ENCODING: "nrdSignalEncoding"],
             floats: [RECONSTRUCTION_MICRO_JITTER_SCALE: "reconstructionMicroJitterScale"],
             resources: ["topLevelAS": "AccelerationStructureIndex",
                         "outputImage": "StorageImageIndex",
                         "stablePlaneMetadataImage": "StorageImageIndex",
                         "normalGuide": "StorageImageIndex",
                         "albedoGuide": "StorageImageIndex", "depthGuide": "StorageImageIndex",
                         "motionGuide": "StorageImageIndex", "specularAlbedoGuide": "StorageImageIndex",
                         "specularMotionGuide": "StorageImageIndex",
                         "diffuseRadianceHitDistance": "StorageImageIndex",
                         "specularRadianceHitDistance": "StorageImageIndex",
                         "nrdViewZ": "StorageImageIndex",
                         "denoisedDiffuseRadianceHitDistance": "StorageImageIndex",
                         "denoisedSpecularRadianceHitDistance": "StorageImageIndex",
                         "nrdStableRadiance": "StorageImageIndex"]],
    ]

    // Gradle decorates this task; closure dispatch cannot resolve a private static helper through it.
    static String upperSnake(String value) {
        value.replaceAll(/([a-z0-9])([A-Z])/, '$1_$2').toUpperCase(Locale.ROOT)
    }

    // Gradle decorates task classes; this must remain non-private for Groovy dispatch inside PIPELINES.each.
    Map reflect(File source, File scratchDir, List<File> externalRoots) {
        def stem = source.name.replaceAll(/\W+/, "-")
        def reflectionFile = new File(scratchDir, "${stem}.json")
        def spvFile = new File(scratchDir, "${stem}.spv")
        def includeDirs = (shaderRoot.get().asFileTree.matching { include "**/*.slang" }.files
                .collect { it.parentFile } + externalRoots.collectMany { CompileSlangShaders.directoryTree(it) })
                .unique().sort { it.absolutePath }
        def includes = [source.parentFile] + includeDirs.findAll { it != source.parentFile }
        execOps.exec {
            commandLine([slangc.get(), source.absolutePath] + includes.collectMany { ["-I", it.absolutePath] } + [
                    "-target", "spirv", "-profile", spirvProfile.get(), "-matrix-layout-column-major",
                    "-warnings-as-errors", "all", "-warnings-disable", "41012",
                    "-reflection-json", reflectionFile.absolutePath, "-o", spvFile.absolutePath])
        }
        execOps.exec {
            commandLine spirvVal.get(), "--target-env", vulkanTarget.get(), spvFile.absolutePath
        }
        new JsonSlurper().parse(reflectionFile) as Map
    }

    @TaskAction
    void generate() {
        def constants = new LinkedHashMap<String, Integer>()
        def scratchDir = new File(temporaryDir, "reflection")
        scratchDir.mkdirs()
        def externalRoots = SlangIncludeRoots.materialize(
                includeDirectories.files, new File(temporaryDir, "jar-includes"))

        PIPELINES.each { spec ->
            def reflection = reflect(new File(shaderRoot.get().asFile, spec.source as String), scratchDir,
                    externalRoots)
            if (spec.pushParameter != null) {
                def descriptors = reflection.parameters.findAll { it.binding?.kind == "descriptorTableSlot" }
                def expectedDescriptors = spec.mappedDescriptors ?: [:]
                def reflectedDescriptors = descriptors.collectEntries { descriptor ->
                    [(descriptor.name): [index: descriptor.binding.index as int,
                                         set: (descriptor.binding.space ?: 0) as int]]
                }
                if (reflectedDescriptors != expectedDescriptors) {
                    throw new GradleException("${spec.source} descriptor mappings differ; expected="
                            + "${expectedDescriptors}, actual=${reflectedDescriptors}")
                }
                def parameter = reflection.parameters.find { it.name == spec.pushParameter }
                def root = parameter?.type?.elementType
                if (parameter?.binding?.kind != "pushConstantBuffer" || root?.name != spec.pushType) {
                    throw new GradleException(
                            "${spec.source} omitted or misshaped ${spec.pushParameter} (expected ${spec.pushType})")
                }
                def rootFields = root.fields.collectEntries { [(it.name): it] }
                def missingAddresses = spec.addresses.values().findAll { !rootFields.containsKey(it) }
                def missingWords = spec.words.values().findAll { !rootFields.containsKey(it) }
                def missingScalars = spec.scalars.values().findAll { !rootFields.containsKey(it) }
                def missingFloats = spec.floats.values().findAll { !rootFields.containsKey(it) }
                def unexpectedRootFields = rootFields.keySet().findAll {
                    it != "resources" && !spec.addresses.containsValue(it)
                            && !spec.words.containsValue(it) && !spec.scalars.containsValue(it)
                            && !spec.floats.containsValue(it)
                }
                if (!missingAddresses.isEmpty() || !missingWords.isEmpty() || !missingScalars.isEmpty()
                        || !missingFloats.isEmpty()
                        || !unexpectedRootFields.isEmpty()) {
                    throw new GradleException("${spec.source} world roots differ; missing="
                            + "${missingAddresses + missingWords + missingScalars + missingFloats}, "
                            + "unexpected=${unexpectedRootFields}")
                }
                spec.addresses.each { constantName, name ->
                    def field = rootFields[name]
                    if (field.type?.kind != "scalar" || field.type?.scalarType != "uint64") {
                        throw new GradleException("${spec.source} ${name} must be a uint64 device address")
                    }
                    constants["WORLD_${constantName}_ADDRESS_OFFSET"] = field.binding.offset as int
                }
                spec.words.each { constantName, name ->
                    def field = rootFields[name]
                    if (field.type?.kind != "scalar" || field.type?.scalarType != "uint64") {
                        throw new GradleException("${spec.source} ${name} must be uint64")
                    }
                    constants["WORLD_${constantName}_OFFSET"] = field.binding.offset as int
                }
                spec.scalars.each { constantName, name ->
                    def field = rootFields[name]
                    if (field.type?.kind != "scalar" || field.type?.scalarType != "uint32") {
                        throw new GradleException("${spec.source} ${name} must be uint32")
                    }
                    constants["WORLD_${constantName}_OFFSET"] = field.binding.offset as int
                }
                spec.floats.each { constantName, name ->
                    def field = rootFields[name]
                    if (field.type?.kind != "scalar" || field.type?.scalarType != "float32") {
                        throw new GradleException("${spec.source} ${name} must be float32")
                    }
                    constants["WORLD_${constantName}_OFFSET"] = field.binding.offset as int
                }
                def resourcesField = rootFields.resources
                def resources = resourcesField?.type
                if (resources?.name != "WorldResourceIndices") {
                    throw new GradleException("${spec.source} resources must be WorldResourceIndices")
                }
                def resourceFields = resources.fields.collectEntries { [(it.name): it] }
                def missingResources = spec.resources.keySet().findAll { !resourceFields.containsKey(it) }
                def unexpectedResources = resourceFields.keySet().findAll { !spec.resources.containsKey(it) }
                if (!missingResources.isEmpty() || !unexpectedResources.isEmpty()) {
                    throw new GradleException("${spec.source} world heap roots differ; missing=${missingResources}, "
                            + "unexpected=${unexpectedResources}")
                }
                spec.resources.each { name, typeName ->
                    def field = resourceFields[name]
                    def valueField = field.type?.fields?.find { it.name == "value" }
                    if (field.type?.name != typeName || valueField?.type?.scalarType != "uint32") {
                        throw new GradleException("${spec.source} ${name} must be ${typeName} wrapping uint32")
                    }
                    constants["WORLD_${upperSnake(name)}_INDEX_OFFSET"] =
                            (resourcesField.binding.offset as int) + (field.binding.offset as int)
                }
                constants.WORLD_PUSH_CONSTANT_SIZE = parameter.type.elementVarLayout.binding.size as int
                return
            }
            def reflected = reflection.parameters.findAll { it.binding?.kind == "descriptorTableSlot" }
                    .collectEntries { [(it.name): it] }
            def missing = spec.resources.values().findAll { !reflected.containsKey(it) }
            def unexpected = reflected.keySet().findAll { !spec.resources.containsValue(it) }
            if (!missing.isEmpty() || !unexpected.isEmpty()) {
                throw new GradleException("${spec.source} descriptor mismatch; missing=${missing}, unexpected=${unexpected}")
            }

            def locations = spec.resources.collectEntries { suffix, resource ->
                def binding = reflected[resource].binding
                [(suffix): [index: binding.index as int, set: (binding.space ?: 0) as int]]
            }
            if (spec.prefix != "WORLD" && (locations.values()*.set as Set).size() != 1) {
                throw new GradleException("${spec.source} resources span unexpected descriptor sets: ${locations}")
            }
            locations.values().groupBy { it.set }.each { set, bindings ->
                def indices = bindings*.index.sort()
                def worldSetWithBindingHole = spec.prefix == "WORLD" && set == 0
                def expected = worldSetWithBindingHole ? [0, 1, 3, 4, 5, 6, 7, 8]
                        : (0..<indices.size()).toList()
                if (indices != expected) {
                    throw new GradleException(
                            "${spec.source} descriptor set ${set} bindings differ; expected=${expected}, actual=${indices}")
                }
            }

            if (spec.prefix.toString().startsWith("OVERLAY_")) {
                constants[spec.prefix as String] = locations.VALUE.index
                def overlaySet = constants.putIfAbsent("OVERLAY_SET", locations.VALUE.set)
                if (overlaySet != null && overlaySet != locations.VALUE.set) {
                    throw new GradleException("overlay shaders disagree on descriptor set: ${overlaySet} and ${locations.VALUE.set}")
                }
            } else {
                constants["${spec.prefix}_SET"] = locations.values().first().set
                locations.each { suffix, location -> constants["${spec.prefix}_${suffix}"] = location.index }
                constants["${spec.prefix}_BINDING_COUNT"] = locations.values()*.index.max() + 1
            }
        }

        def generatedRoot = outDir.get().asFile
        if (generatedRoot.exists() && !generatedRoot.deleteDir()) {
            throw new GradleException("failed to clear generated binding output ${generatedRoot}")
        }
        def packageName = javaPackage.get()
        def output = outDir.get().file(packageName.replace('.', '/') + "/RtBindings.java").asFile
        output.parentFile.mkdirs()
        output.setText("""// Generated from Slang descriptor reflection. Do not edit.
package ${packageName};

public final class RtBindings {
${constants.collect { name, value -> "    public static final int ${name} = ${value};" }.join('\n')}

    private RtBindings() {
    }
}
""", "UTF-8")
    }
}
