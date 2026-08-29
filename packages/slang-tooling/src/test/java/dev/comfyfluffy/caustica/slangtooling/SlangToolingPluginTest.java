package dev.comfyfluffy.caustica.slangtooling;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
                    doLast {
                        println "profile=${spirvProfile.get()} target=${vulkanTarget.get()}"
                    }
                }
                """);
        Files.createDirectories(project.resolve("shaders"));

        var result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("inspectTooling", "--stacktrace").build();

        assertEquals(SUCCESS, result.task(":inspectTooling").getOutcome());
        assertTrue(result.getOutput().contains("profile=spirv_1_6 target=vulkan1.4"));
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
}
