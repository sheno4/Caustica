package dev.comfyfluffy.caustica.slangtooling;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
