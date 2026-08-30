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

    @Test
    void contentPublicationSharesOneNativeGeometryReferenceAndUsesTheOrderedQueue() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtRetainedSceneBackend.java"));

        int method = source.indexOf("void publishContent(RetainedSceneContentSnapshot snapshot");
        int end = source.indexOf("private long tailRevision()", method);
        String body = source.substring(method, end);
        assertTrue(body.contains("predecessor.geometry.retain();"));
        assertTrue(body.contains("queued.addLast(publication);"));
        assertTrue(body.contains("assembleContent(snapshot.scenes(), snapshot.lights())"));
        assertTrue(!body.contains("geometry.meshes"));
        assertTrue(!body.contains("geometry.instances"));
    }

    @Test
    void productionGeometryPublicationNeverMaterializesTheFallbackSnapshot() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtRetainedSceneBackend.java"));

        int method = source.indexOf("void publishGeometry(RetainedSceneGeometryDelta delta");
        int end = source.indexOf("void publishContent(RetainedSceneContentSnapshot snapshot", method);
        String body = source.substring(method, end);
        assertTrue(body.contains("prepare(delta, predecessor)"));
        assertTrue(!body.contains("fallbackSnapshot.get("));
    }
}
