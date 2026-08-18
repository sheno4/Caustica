package dev.comfyfluffy.caustica.build

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
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

    @Input abstract Property<String> getSlangc()
    @OutputDirectory abstract DirectoryProperty getOutDir()

    @Inject abstract ExecOperations getExecOps()

    private static final List<Map> PIPELINES = [
            [prefix: "WORLD", source: "pipelines/world/primary_reflection.rgen.slang", resources: [
                    TLAS: "topLevelAS", OUTPUT: "outImage",
                    G_NORMAL: "gNormal", G_ALBEDO: "gAlbedo", G_DEPTH: "gDepth", G_MOTION: "gMotion",
                    G_SPEC_ALBEDO: "gSpecAlbedo", G_SPEC_MOTION: "gSpecMotion",
                    // The sky-view/transmittance LUTs, the celestials atlas, and the sky's per-frame
                    // inputs are none of them engine-fixed set-0 bindings: the selected pass declares
                    // them at set 2, discovered from per-composition runtime reflection instead of this
                    // build-time one.
                    BASE_COLOR_TEXTURES: "baseColorTextures", MATERIAL_SURFACE0: "materialSurface0Tex",
                    MATERIAL_NORMAL: "materialNormalTex", MATERIAL_SURFACE1: "materialSurface1Tex",
                    MATERIAL_EMISSION: "materialEmissionTex"]],
            [prefix: "DISPLAY", source: "pipelines/display/main.comp.slang", resources: [
                    OUTPUT: "outputImage", RT_IMAGE: "rtImage", EXPOSURE: "exposureImage", HDR_OUTPUT: "hdrImage",
                    SDR_TONE_LUT: "toneLut", HDR_TONE_LUT: "hdrToneLut", LOOK_LUT: "lookLut"]],
            [prefix: "DEBUG_PRESENT", source: "pipelines/debug_present/main.comp.slang", resources: [
                    OUTPUT: "outputImage", G_NORMAL: "gNormal", G_ALBEDO: "gAlbedo", G_DEPTH: "gDepth",
                    G_MOTION: "gMotion", G_SPEC_ALBEDO: "gSpecAlbedo", G_SPEC_MOTION: "gSpecMotion",
                    SCENE: "sceneImage", EXPOSURE: "exposureImage", EXPOSURE_STATE: "exposureState"]],
            [prefix: "EXPOSURE_HIST", source: "pipelines/exposure_hist/main.comp.slang", resources: [
                    COLOR: "colorImage", BINS: "histBins", DEPTH: "depthImage", ALBEDO: "albedoImage"]],
            [prefix: "EXPOSURE_RESOLVE", source: "pipelines/exposure_resolve/main.comp.slang", resources: [
                    HIST_BINS: "histBins", IMAGE: "exposureImage", STATE: "stateBuf"]],
            [prefix: "PRESENT", source: "pipelines/hdr_composite/main.comp.slang", resources: [
                    OUTPUT: "outputImage", SOURCE: "sourceImage"]],
            [prefix: "OVERLAY_IMAGE", source: "pipelines/overlay_composite/glow.frag.slang", resources: [VALUE: "sourceImage"]],
            [prefix: "OVERLAY_SAMPLER", source: "pipelines/name_tag/fragment.frag.slang", resources: [VALUE: "fontAtlas"]],
            [prefix: "OVERLAY_TLAS", source: "pipelines/block_outline/fragment.frag.slang", resources: [VALUE: "tlas"]]
    ]

    // Gradle decorates task classes; this must remain non-private for Groovy dispatch inside PIPELINES.each.
    Map reflect(File source, File scratchDir) {
        def stem = source.name.replaceAll(/\W+/, "-")
        def reflectionFile = new File(scratchDir, "${stem}.json")
        def spvFile = new File(scratchDir, "${stem}.spv")
        def includeDirs = shaderRoot.get().asFileTree.matching { include "**/*.slang" }.files
                .collect { it.parentFile }.unique().sort { it.absolutePath }
        def includes = [source.parentFile] + includeDirs.findAll { it != source.parentFile }
        execOps.exec {
            commandLine([slangc.get(), source.absolutePath] + includes.collectMany { ["-I", it.absolutePath] } + [
                    "-target", "spirv", "-profile", "spirv_1_5", "-matrix-layout-column-major",
                    "-warnings-as-errors", "all", "-warnings-disable", "41012",
                    "-reflection-json", reflectionFile.absolutePath, "-o", spvFile.absolutePath])
        }
        new JsonSlurper().parse(reflectionFile) as Map
    }

    @TaskAction
    void generate() {
        def constants = new LinkedHashMap<String, Integer>()
        def scratchDir = new File(temporaryDir, "reflection")
        scratchDir.mkdirs()

        PIPELINES.each { spec ->
            def reflection = reflect(new File(shaderRoot.get().asFile, spec.source as String), scratchDir)
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
            } else if (spec.prefix == "WORLD") {
                def ordinary = locations.findAll { suffix, ignored -> suffix != "BASE_COLOR_TEXTURES" && !(suffix as String).startsWith("MATERIAL_") }
                def bindless = locations.findAll { suffix, ignored -> suffix == "BASE_COLOR_TEXTURES" || (suffix as String).startsWith("MATERIAL_") }
                if ((ordinary.values()*.set as Set).size() != 1 || (bindless.values()*.set as Set).size() != 1
                        || ordinary.values().first().set == bindless.values().first().set) {
                    throw new GradleException("world resources do not have distinct ordinary and bindless sets: ${locations}")
                }
                constants.WORLD_SET = ordinary.values().first().set
                ordinary.each { suffix, location -> constants["WORLD_${suffix}"] = location.index }
                def guides = ordinary.findAll { suffix, ignored -> (suffix as String).startsWith("G_") }
                def storageImages = guides + ordinary.findAll { suffix, ignored -> suffix == "OUTPUT" }
                def samplers = ordinary.findAll { suffix, ignored -> suffix != "TLAS" && !storageImages.containsKey(suffix) }
                constants.WORLD_GUIDE_COUNT = guides.size()
                constants.WORLD_SET_DESCRIPTOR_COUNT = ordinary.size()
                constants.WORLD_SET_BINDING_COUNT = ordinary.values()*.index.max() + 1
                constants.WORLD_SET_STORAGE_IMAGE_COUNT = storageImages.size()
                constants.WORLD_SET_SAMPLER_COUNT = samplers.size()
                constants.WORLD_BINDLESS_SET = bindless.values().first().set
                bindless.each { suffix, location -> constants["WORLD_${suffix}"] = location.index }
                constants.WORLD_BINDLESS_COUNT = bindless.size()
            } else {
                constants["${spec.prefix}_SET"] = locations.values().first().set
                locations.each { suffix, location -> constants["${spec.prefix}_${suffix}"] = location.index }
                constants["${spec.prefix}_BINDING_COUNT"] = locations.values()*.index.max() + 1
            }
        }

        def output = outDir.get().file("dev/comfyfluffy/caustica/rt/pipeline/RtBindings.java").asFile
        output.parentFile.mkdirs()
        output.setText("""// Generated from Slang descriptor reflection. Do not edit.
package dev.comfyfluffy.caustica.rt.pipeline;

public final class RtBindings {
${constants.collect { name, value -> "    public static final int ${name} = ${value};" }.join('\n')}

    private RtBindings() {
    }
}
""", "UTF-8")
    }
}
