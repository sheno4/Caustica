package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtRetainedScenePublicationContractTest {
    @Test
    void completedBlasBuildIsMarkedBeforeItsSnapshotBecomesVisible() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtRetainedSceneBackend.java"));

        assertTrue(source.contains("(build, failure) -> completeLater(publication, build, failure)"));
        int finishStart = source.indexOf("private void finishBuild(Publication publication)");
        int mark = source.indexOf("ctx.gpuExecutor().markPublished(publication.build);", finishStart);
        int publish = source.indexOf("published = publication.candidate.publish();", finishStart);
        assertTrue(finishStart >= 0 && mark > finishStart && publish > mark);
    }

    @Test
    void reusedOnlySnapshotPublishesWithoutAnAsyncBuildWait() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtRetainedSceneBackend.java"));

        int emptyBuilds = source.indexOf("if (candidate.builds.isEmpty())");
        int completion = source.indexOf("completeLater(publication, null, null);", emptyBuilds);
        assertTrue(emptyBuilds >= 0 && completion > emptyBuilds);
    }
}
