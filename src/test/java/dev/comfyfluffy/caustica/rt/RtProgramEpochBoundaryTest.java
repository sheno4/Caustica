package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtProgramEpochBoundaryTest {
    private static final Path RT = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "rt")
            .toAbsolutePath().normalize();

    @Test
    void processProgramManagerOwnsWorldCompilation() throws IOException {
        String manager = Files.readString(RT.resolve("RtProgramManager.java"));
        String composite = Files.readString(RT.resolve("RtComposite.java"));

        assertTrue(manager.contains("WorldShaderCompiler.createIsolated"));
        assertTrue(manager.contains("ExecutorService BUILD_EXECUTOR"));
        assertFalse(composite.contains("WorldShaderCompiler.createIsolated"));
        assertFalse(composite.contains("SHADER_BUILD_EXECUTOR"));
    }

    @Test
    void programReplacementReusesThePublishedResourcePackEpoch() throws IOException {
        String world = Files.readString(RT.resolve("RtWorldResources.java"));
        String replacement = methodBody(world, "private void replaceProgram", "private void bindPassResources");

        assertTrue(replacement.contains("materialEpoch.bindCurrent"));
        assertFalse(replacement.contains("materialEpoch.publish"));
        assertFalse(replacement.contains("RtMaterialRegistry.INSTANCE.rebuild"));
        assertFalse(replacement.contains("ProviderManager.INSTANCE.invalidateScenes"));
        assertFalse(replacement.contains("resetBindlessTextures"));
        assertTrue(replacement.indexOf("pipeline = replacement")
                < replacement.indexOf("programManager.activate(candidate)"));

        String epoch = Files.readString(RT.resolve("material/RtMaterialEpoch.java"));
        String bindCurrent = methodBody(epoch, "public void bindCurrent", "public void uploadPendingTextures");
        assertTrue(bindCurrent.contains("rebindTextures"));
        assertFalse(bindCurrent.contains("resetBindlessTextures"));
    }

    @Test
    void programCompilationIsRequestedBeforeRtActivation() throws IOException {
        String runtime = Files.readString(RT.resolve("RtRuntime.java"));
        int request = runtime.indexOf("RtProgramManager.INSTANCE.request");
        int enabled = runtime.indexOf("boolean requested = CausticaConfig.Rt.ENABLED.value()", request);

        assertTrue(request >= 0);
        assertTrue(enabled > request);
    }

    @Test
    void slotChangeWaitsForItsCandidateBeforeReplacingTheSession() throws IOException {
        String runtime = Files.readString(RT.resolve("RtRuntime.java"));
        int candidate = runtime.indexOf("RtProgramManager.Program candidate = RtProgramManager.INSTANCE.candidate()");
        int replacement = runtime.indexOf("!session.matches(selection) && candidate != null", candidate);
        int start = runtime.indexOf("if (state == State.OFF)", replacement);

        assertTrue(candidate >= 0);
        assertTrue(replacement > candidate);
        assertTrue(start > replacement);
    }

    private static String methodBody(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start, "Could not locate program replacement method");
        return source.substring(start, end);
    }
}
