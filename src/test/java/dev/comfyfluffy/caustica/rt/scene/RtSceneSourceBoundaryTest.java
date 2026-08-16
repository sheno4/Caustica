package dev.comfyfluffy.caustica.rt.scene;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSceneSourceBoundaryTest {
    private static final Path JAVA = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica")
            .toAbsolutePath().normalize();

    @Test
    void rendererAssemblyDoesNotImportSourceImplementations() throws IOException {
        for (String relative : List.of("rt/RtComposite.java", "rt/RtRuntime.java")) {
            Path source = JAVA.resolve(relative);
            String content = Files.readString(source);
            assertFalse(content.contains("dev.comfyfluffy.caustica.rt.terrain"), source.toString());
            assertFalse(content.contains("dev.comfyfluffy.caustica.rt.entity"), source.toString());
            assertFalse(content.contains("RtTerrain"), source.toString());
            assertFalse(content.contains("RtEntities"), source.toString());
            assertFalse(content.contains("RtEntityTextures"), source.toString());
        }
    }

    @Test
    void sceneSourceContractIsHostNeutral() throws IOException {
        Path source = JAVA.resolve("rt/scene/RtSceneSource.java");
        String content = Files.readString(source);
        String lower = content.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("net.minecraft"), source.toString());
        assertFalse(lower.contains("net.fabricmc"), source.toString());
        assertFalse(lower.contains("net.neoforged"), source.toString());
        assertFalse(lower.contains("rt.terrain"), source.toString());
        assertFalse(lower.contains("rt.entity"), source.toString());
        assertFalse(lower.matches("(?s).*\\b(minecraft|terrain|entity|section|block)\\b.*"), source.toString());
        assertFalse(content.contains("PreparedBlas"), source.toString());
        assertFalse(content.contains("GraphicsUse"), source.toString());
        assertFalse(content.contains("RtAccel"), source.toString());
        assertFalse(content.contains("GpuBuffer"), source.toString());
        assertFalse(content.contains("geometryTableAddress"), source.toString());
        assertFalse(content.contains("MotionInput"), source.toString());
        assertFalse(content.contains("RtSceneGeometryManager.FrameUpdate"), source.toString());
        assertFalse(content.contains("RtSceneGeometryManager"), source.toString());
        assertTrue(content.contains("bindlessTextureSlot"), source.toString());
    }

    @Test
    void updateDoesNotExposeRawRecordsOrInstances() throws ReflectiveOperationException {
        var update = dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager.FrameUpdate.class;
        assertFalse(java.lang.reflect.Modifier.isPublic(update.getDeclaredMethod("appendRecord", long.class,
                long.class, long.class, long.class, int.class, int[].class, int.class).getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(update.getDeclaredMethod("appendInstance", float[].class,
                long.class, int.class, int.class,
                Class.forName("dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager$InstanceKey"),
                boolean.class).getModifiers()));
        assertFalse(java.util.Arrays.stream(update.getMethods())
                .anyMatch(method -> method.getName().equals("instances") || method.getName().equals("blasBuilds")
                        || method.getName().equals("uploadMotion") || method.getName().equals("geometryTableAddress")));
    }

    @Test
    void minecraftProviderOwnsThePrimarySceneAdapterAndWorkerStop() throws IOException {
        String provider = Files.readString(JAVA.resolve("minecraft/provider/MinecraftSceneProvider.java"));
        assertTrue(provider.contains("implements SceneProvider, RtSceneSource"));
        assertTrue(provider.contains("RtWorkerPool.INSTANCE.shutdown()"));
        assertFalse(provider.contains("RtComposite.INSTANCE"), provider);
        assertFalse(provider.contains("RtSceneGeometryManager"), provider);
        assertFalse(provider.contains("PackedInput"), provider);
        assertFalse(provider.contains("RetainedPayload"), provider);
        assertFalse(provider.contains("RtAccel"), provider);

        String runtime = Files.readString(JAVA.resolve("rt/RtRuntime.java"));
        assertTrue(runtime.contains("host().resetSceneTextures()"));
        assertFalse(runtime.contains("RtWorkerPool.INSTANCE"));
    }

    @Test
    void frameLifetimeAndUploadOrderingRemainExplicit() throws IOException {
        String composite = Files.readString(JAVA.resolve("rt/RtFrameRenderer.java"));
        int upload = composite.indexOf("materialEpoch.uploadPendingTextures");
        int tlas = composite.indexOf("sceneGeometry.prepareTlas", upload);
        int execute = composite.indexOf("submission.execute(cmd)");
        int markPush = composite.indexOf("framePushSlot.graphicsUse.mark", execute);
        assertTrue(upload >= 0 && upload < tlas, "source textures must publish before TLAS recording");
        assertFalse(composite.contains("recordBlasBuilds"),
                "frame assembly must not own provider BLAS recording");
        assertTrue(execute < markPush,
                "the push-ring slot must not retain a token until graphics submission succeeds");
        assertFalse(composite.substring(0, execute).contains("selectedPushSlot.graphicsUse.mark"),
                "a failed recording must leave the reserved push-ring slot reusable");
        int isolatedSourceFailure = composite.indexOf(
                "catch (ProviderManager.SceneSourceUnavailableException unavailable)");
        int globalFailure = composite.indexOf("failed = true", isolatedSourceFailure);
        assertTrue(isolatedSourceFailure >= 0 && isolatedSourceFailure < globalFailure,
                "a scene-source failure must fall back without disabling the renderer");

        String runtime = Files.readString(JAVA.resolve("rt/RtRuntime.java"));
        int stop = runtime.indexOf("ProviderManager.INSTANCE.stopProviders()");
        int drain = runtime.indexOf("context.gpuExecutor().drainAndWaitIdle()", stop);
        int compositeDestroy = runtime.indexOf("RtComposite.INSTANCE.destroy()", drain);
        int shutdown = runtime.indexOf("ProviderManager.INSTANCE.shutdownResources()", compositeDestroy);
        assertTrue(stop >= 0 && stop < drain && drain < compositeDestroy && compositeDestroy < shutdown,
                "world descriptors must be destroyed after idle and before provider GPU textures");
        int update = runtime.indexOf("ProviderManager.INSTANCE.updateScenes()");
        int startupGeometry = runtime.indexOf("ProviderManager.INSTANCE.submitGeometryUpdates", update);
        int geometryProgress = runtime.indexOf("RtComposite.INSTANCE.sceneGeometry().progress", startupGeometry);
        assertTrue(update >= 0 && update < startupGeometry && startupGeometry < geometryProgress,
                "startup must submit update-cadence geometry before polling retained publication");
    }
}
