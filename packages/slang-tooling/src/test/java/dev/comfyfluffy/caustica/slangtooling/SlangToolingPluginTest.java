package dev.comfyfluffy.caustica.slangtooling;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SlangToolingPluginTest {
    @Test
    void appliesOutsideTheRootBuildAndExposesTypedTaskConventions(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'dev.comfyfluffy.caustica.slang-tooling' }
                tasks.register('inspectTooling', dev.comfyfluffy.caustica.slangtooling.CompileSlangShaders) {
                    sourceDirectory = layout.projectDirectory.dir('shaders')
                    aliasSourceDirectory = layout.projectDirectory.dir('shaders')
                    outputDirectory = layout.buildDirectory.dir('spirv')
                    descriptorHeapNative = true
                    doLast {
                        println "profile=${spirvProfile.get()} target=${vulkanTarget.get()} heap=${descriptorHeapNative.get()}"
                    }
                }
                """);
        Files.createDirectories(project.resolve("shaders"));

        var result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("inspectTooling", "--stacktrace").build();

        assertEquals(SUCCESS, result.task(":inspectTooling").getOutcome());
        assertTrue(result.getOutput().contains("profile=spirv_1_6 target=vulkan1.4 heap=true"));
    }

    @Test
    void acceptsPackageLocalShaderRecordSpecifications(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'dev.comfyfluffy.caustica.slang-tooling' }
                tasks.register('packageRecords', dev.comfyfluffy.caustica.slangtooling.GenerateShaderRecords) {
                    recordSpecs = ['push|localProbe|LocalPush|example.feature.gen|LocalPushData|false']
                }
                tasks.register('inspectRecords') {
                    doLast {
                        def records = tasks.named('packageRecords').get()
                        println "records=${records.recordSpecs.get()}"
                    }
                }
                """);

        var result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("inspectRecords", "--stacktrace").build();

        assertEquals(SUCCESS, result.task(":inspectRecords").getOutcome());
        assertTrue(result.getOutput().contains(
                "records=[push|localProbe|LocalPush|example.feature.gen|LocalPushData|false]"));
    }

    @Test
    void rejectsUnknownRecordKinds() {
        var error = assertThrows(GradleException.class, () -> GenerateShaderRecords.parseRecordSpecs(
                java.util.List.of("texture|probe|Record|example.gen|RecordData|false")));

        assertTrue(error.getMessage().contains("unknown shader record kind 'texture'"));
    }

    @Test
    void rejectsMalformedRecordSpecifications() {
        var error = assertThrows(GradleException.class, () -> GenerateShaderRecords.parseRecordSpecs(
                java.util.List.of("push|probe|Record|example.gen|RecordData")));

        assertTrue(error.getMessage().contains("expected kind|probe|struct|package|class|reader"));
    }

    @Test
    void rejectsDuplicateRecordOutputs() {
        var error = assertThrows(GradleException.class, () -> GenerateShaderRecords.parseRecordSpecs(
                java.util.List.of(
                        "buffer|firstProbe|FirstRecord|example.gen|RecordData|false",
                        "push|secondProbe|SecondRecord|example.gen|RecordData|false")));

        assertTrue(error.getMessage().contains("duplicate generated shader record 'example.gen.RecordData'"));
    }

    @Test
    void extractsOnlySlangModulesFromJarIncludes(@TempDir Path project) throws Exception {
        var archive = project.resolve("shader-api.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
            writeEntry(output, "caustica/shaders/api/z.slang", "module z;");
            writeEntry(output, "META-INF/NOTICE.txt", "not a shader");
            writeEntry(output, "caustica/shaders/api/a.slang", "module a;");
        }

        var roots = SlangIncludeRoots.materialize(List.of(archive.toFile()), project.resolve("includes").toFile());

        assertEquals(1, roots.size());
        assertTrue(roots.get(0).toPath().endsWith(Path.of("includes", "0000")));
        assertEquals("module a;", Files.readString(
                roots.get(0).toPath().resolve("caustica/shaders/api/a.slang")));
        assertEquals("module z;", Files.readString(
                roots.get(0).toPath().resolve("caustica/shaders/api/z.slang")));
        assertEquals(roots.get(0).toPath().resolve("caustica/shaders/api/a.slang").toFile(),
                CompileSlangShaders.resolveAliasSource(null, "caustica/shaders/api/a.slang", roots));
        assertTrue(Files.notExists(roots.get(0).toPath().resolve("META-INF/NOTICE.txt")));
    }

    @Test
    void rejectsEscapingJarIncludeEntries(@TempDir Path project) throws Exception {
        var archive = project.resolve("unsafe.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
            writeEntry(output, "../escape.slang", "module escape;");
        }

        var error = assertThrows(GradleException.class, () -> SlangIncludeRoots.materialize(
                List.of(archive.toFile()), project.resolve("includes").toFile()));

        assertTrue(error.getMessage().contains("escapes its include root"));
        assertTrue(Files.notExists(project.resolve("escape.slang")));
    }

    private static void writeEntry(JarOutputStream output, String name, String contents) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
