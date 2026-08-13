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
    void optimizedSceneContractIsHostNeutral() throws IOException {
        Path source = JAVA.resolve("rt/scene/RtSceneSource.java");
        String content = Files.readString(source);
        String lower = content.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("net.minecraft"), source.toString());
        assertFalse(lower.contains("net.fabricmc"), source.toString());
        assertFalse(lower.contains("rt.terrain"), source.toString());
        assertFalse(lower.contains("rt.entity"), source.toString());
        assertFalse(lower.matches("(?s).*\\b(minecraft|terrain|entity|section|block)\\b.*"), source.toString());
    }

    @Test
    void minecraftProviderOwnsTheOptimizedAdapterAndWorkerStop() throws IOException {
        String provider = Files.readString(JAVA.resolve("minecraft/provider/MinecraftSceneProvider.java"));
        assertTrue(provider.contains("implements SceneProvider, RtSceneSource"));
        assertTrue(provider.contains("RtWorkerPool.INSTANCE.shutdown()"));

        String runtime = Files.readString(JAVA.resolve("rt/RtRuntime.java"));
        assertTrue(runtime.contains("host().resetSceneTextures()"));
        assertFalse(runtime.contains("RtWorkerPool.INSTANCE"));
    }

    @Test
    void frameLifetimeAndUploadOrderingRemainExplicit() throws IOException {
        String composite = Files.readString(JAVA.resolve("rt/RtComposite.java"));
        int upload = composite.indexOf("ProviderManager.INSTANCE.uploadPendingTextures");
        int blas = composite.indexOf("RtAccel.recordBlasBuilds", upload);
        int execute = composite.indexOf("submission.execute(cmd)");
        int markPush = composite.indexOf("framePushSlot.graphicsUse.mark", execute);
        int markSource = composite.indexOf("sourceFrame.markGraphicsUse", execute);
        assertTrue(upload >= 0 && upload < blas, "source textures must publish before BLAS/TLAS recording");
        assertTrue(execute >= 0 && execute < markSource,
                "source lifetimes must attach only after graphics submission succeeds");
        assertTrue(execute < markPush,
                "the push-ring slot must not retain a token until graphics submission succeeds");
        assertFalse(composite.substring(0, execute).contains("selectedPushSlot.graphicsUse.mark"),
                "a failed recording must leave the reserved push-ring slot reusable");
        int isolatedSourceFailure = composite.indexOf(
                "catch (ProviderManager.SceneSourceUnavailableException unavailable)");
        int globalFailure = composite.indexOf("failed = true", isolatedSourceFailure);
        assertTrue(isolatedSourceFailure >= 0 && isolatedSourceFailure < globalFailure,
                "an optimized source failure must fall back without disabling the renderer");

        String runtime = Files.readString(JAVA.resolve("rt/RtRuntime.java"));
        int stop = runtime.indexOf("ProviderManager.INSTANCE.stopProviders()");
        int drain = runtime.indexOf("context.gpuExecutor().drainAndWaitIdle()", stop);
        int shutdown = runtime.indexOf("ProviderManager.INSTANCE.shutdownResources()", drain);
        assertTrue(stop >= 0 && stop < drain && drain < shutdown,
                "source work must stop before GPU drain and release after idle");
    }
}
