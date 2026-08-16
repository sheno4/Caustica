package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialEpochBoundaryTest {
    private static final Path WORLD_RESOURCES = Path.of(
            "src/main/java/dev/comfyfluffy/caustica/rt/RtWorldResources.java");

    @Test
    void worldResourcesOwnMaterialEpochThroughOneBoundary() throws Exception {
        String source = Files.readString(WORLD_RESOURCES);

        assertTrue(source.contains("final RtMaterialEpoch materialEpoch"));
        assertTrue(source.contains("materialEpoch.publish("));
        assertTrue(source.contains("materialEpoch.bindCurrent(context, replacement)"));
        assertFalse(source.contains("RtMaterialPageCompiler"));
        assertFalse(source.contains("RtMaterialRegistry"));
        assertFalse(source.contains("RtOpacityMicromapPipeline"));
        assertFalse(source.contains("materialTextureSampler"));
    }

    @Test
    void lifecycleStateSeparatesReloadFromPublishedResourceDestruction() {
        RtMaterialEpoch.LifecycleState state = new RtMaterialEpoch.LifecycleState();
        state.published(64);
        assertTrue(state.bindingsReady);
        assertTrue(state.hasPublishedResources());
        assertEquals(64, state.bindlessTextureCapacity);

        state.beginReload();
        assertTrue(state.reloadPending);
        assertFalse(state.bindingsReady);
        assertEquals(64, state.bindlessTextureCapacity,
                "the caller still needs the active pipeline capacity until descriptor destruction");

        state.destroyPublished();
        assertTrue(state.reloadPending);
        assertEquals(0, state.bindlessTextureCapacity);
        state.reloadFailed();
        assertFalse(state.reloadPending);
    }

    @Test
    void reloadWithoutContextRejectsLiveResourcesBeforeChangingState() throws Exception {
        String source = Files.readString(WORLD_RESOURCES);
        int reload = source.indexOf("void beginReload(");
        int context = source.indexOf("context == null", reload);
        int rejectLive = source.indexOf("materialEpoch.hasPublishedResources()", context);
        int begin = source.indexOf("materialEpoch.beginReload()", rejectLive);

        assertTrue(reload >= 0 && reload < context && context < rejectLive && rejectLive < begin);
    }

    @Test
    void capacityGrowthInvalidatesAndDrainsBeforeDestroyingTheEpoch() throws Exception {
        String source = Files.readString(WORLD_RESOURCES);
        int refresh = source.indexOf("void refreshShape(");
        int growth = source.indexOf("desiredCapacity > materialEpoch.bindlessTextureCapacity()", refresh);
        int invalidate = source.indexOf("invalidateMaterialBindings()", growth);
        int drain = source.indexOf("context.gpuExecutor().drainAndWaitIdle()", invalidate);
        int pipeline = source.indexOf("pipeline.destroy()", drain);
        int epoch = source.indexOf("materialEpoch.destroyPublishedEpoch()", pipeline);

        assertTrue(refresh >= 0 && refresh < growth && growth < invalidate && invalidate < drain
                && drain < pipeline && pipeline < epoch);
    }

    @Test
    void capacityRebuildSelectsPendingProgramAndDesiredProviderCapacity() throws Exception {
        String source = Files.readString(WORLD_RESOURCES);
        int ensure = source.indexOf("RtPipeline ensureWorld");
        int candidate = source.indexOf("RtProgramManager.Program program = programManager.candidate()", ensure);
        int capacity = source.indexOf(
                "int bindlessCapacity = ProviderManager.INSTANCE.bindlessTextureCapacity()", candidate);
        int create = source.indexOf("createPipeline(context, program, bindlessCapacity)", capacity);
        int publish = source.indexOf("materialEpoch.publish(context, created, bindlessCapacity)", create);

        assertTrue(ensure >= 0 && ensure < candidate && candidate < capacity && capacity < create && create < publish);
    }

    @Test
    void failedInitialAssemblyDestroysDescriptorsBeforeEpochEvenWhenDescriptorDestroyFails() throws Exception {
        String source = Files.readString(WORLD_RESOURCES);
        int ensure = source.indexOf("RtPipeline ensureWorld");
        int failure = source.indexOf("catch (Throwable failure)", ensure);
        int pipeline = source.indexOf("created.destroy()", failure);
        int cleanupFinally = source.indexOf("finally", pipeline);
        int epoch = source.indexOf("materialEpoch.destroyPublishedEpoch()", cleanupFinally);

        assertTrue(ensure >= 0 && ensure < failure && failure < pipeline
                && pipeline < cleanupFinally && cleanupFinally < epoch);
    }
}
